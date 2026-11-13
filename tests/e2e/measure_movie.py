"""Measure upload, inspection, audio extraction and an export proxy on a REAL movie.

Usage (stack running):
  apps\\ai\\.venv\\Scripts\\python.exe tests\\e2e\\measure_movie.py --movie "D:\\path\\movie.mkv" [--stream N]

The movie is only read from disk and imported through the app (a copy lands in the storage root).
Nothing is sent to any cloud service. Prints one JSON report.

Comparisons:
  * extraction in the Linux worker (segments + merge, files on the Windows bind mount) vs a single-pass
    extraction by the Windows FFmpeg build reading the same file natively;
  * export proxy (copy video + encode the extracted track to AAC into a new MP4) in the Linux worker
    (Debian FFmpeg 7.1.5, bind mount) vs Windows (FFmpeg 9.0.2, native disk).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import stack  # noqa: E402


def timed(cmd: list[str]) -> float:
    started = time.monotonic()
    subprocess.run(cmd, check=True, capture_output=True)
    return round(time.monotonic() - started, 1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--movie", required=True)
    ap.add_argument("--stream", type=int, default=None, help="audio stream index (default: the project's default)")
    args = ap.parse_args()
    movie = Path(args.movie)
    size = movie.stat().st_size
    report: dict = {"movie": movie.name, "sizeBytes": size}

    # 1. Upload (browser path: straight to Spring Boot, streamed to D:)
    pid = stack.create_project(f"Measurement: {movie.stem}")
    started = time.monotonic()
    r = stack.upload(pid, movie)
    r.raise_for_status()
    upload_s = time.monotonic() - started
    report["upload"] = {"seconds": round(upload_s, 1), "MBps": round(size / 2**20 / upload_s, 1)}

    # 2. Inspection (ffprobe in the Linux worker)
    imp = stack.wait_job(r.json()["importJob"]["id"], {"COMPLETED", "FAILED"}, 900)
    if imp["status"] != "COMPLETED":
        report["import"] = {"status": imp["status"], "error": imp["errorMessage"]}
        print(json.dumps(report, indent=2))
        return 1
    detail = stack.project(pid)
    probe = detail["original"]["metadata"]["probe"]
    inspect_attempt = imp["chunks"][0]["attempts"][0]
    report["inspection"] = {"probeMs": inspect_attempt["metrics"].get("probeMs"),
                            "wallMs": inspect_attempt["metrics"].get("wallMs"),
                            "durationMin": round(probe["durationMs"] / 60000, 1), "format": probe["formatName"],
                            "video": probe["videoStreams"], "audio": probe["audioStreams"]}
    stream = args.stream if args.stream is not None else detail["sourceAudioStreamIndex"]

    # 3. Extraction job (segments + merge on the Linux worker)
    started = time.monotonic()
    j = stack.wait_job(stack.start_extraction(pid, stream), {"COMPLETED", "FAILED"}, 7200)
    job_s = time.monotonic() - started
    if j["status"] != "COMPLETED":
        report["extraction"] = {"status": j["status"], "error": j["errorMessage"]}
        print(json.dumps(report, indent=2))
        return 1
    segs = [c["attempts"][-1]["metrics"] for c in j["chunks"] if c["stage"] == "EXTRACT_AUDIO"]
    merge = next(c for c in j["chunks"] if c["stage"] == "MERGE_AUDIO")["attempts"][-1]["metrics"]
    result = stack.asset(j["resultAssetId"])
    report["extraction"] = {
        "jobSeconds": round(job_s, 1), "segments": len(segs),
        "segmentFfmpegS": {"min": min(s["ffmpegMs"] for s in segs) / 1000, "max": max(s["ffmpegMs"] for s in segs) / 1000,
                           "sum": round(sum(s["ffmpegMs"] for s in segs) / 1000, 1)},
        "segmentWallSumS": round(sum(s["wallMs"] for s in segs) / 1000, 1),
        "mergeS": merge["mergeMs"] / 1000, "mergeHashS": merge["hashMs"] / 1000,
        "engineOverheadS": round(job_s - sum(s["wallMs"] for s in segs) / 1000 - merge["wallMs"] / 1000, 1),
        "outputBytes": result["sizeBytes"]}

    original = stack.DATA / detail["original"]["storagePath"]
    merged = stack.DATA / result["storagePath"]
    with tempfile.TemporaryDirectory() as tmp:
        # 4. Baseline: one-pass extraction with the Windows FFmpeg build, native disk access
        report["baselineWindowsSinglePassS"] = timed([
            stack.FFMPEG, "-hide_banner", "-v", "error", "-y", "-i", str(original), "-map", f"0:{stream}",
            "-af", "aresample=16000", "-ac", "1", "-c:a", "pcm_s16le", str(Path(tmp) / "baseline.wav")])

        # 5. Export proxy: copy video + encode the extracted track to AAC, Windows native vs Linux worker
        report["exportProxyWindowsS"] = timed([
            stack.FFMPEG, "-hide_banner", "-v", "error", "-y", "-i", str(original), "-i", str(merged),
            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-shortest",
            str(Path(tmp) / "export.mp4")])
    bench_rel = f"temp/bench-export-{pid}.mp4"
    try:
        report["exportProxyLinuxWorkerS"] = timed([
            "docker", "exec", stack.WORKER, "ffmpeg", "-hide_banner", "-v", "error", "-y",
            "-i", f"/data/{detail['original']['storagePath']}", "-i", f"/data/{result['storagePath']}",
            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-shortest",
            f"/data/{bench_rel}"])
    finally:
        (stack.DATA / bench_rel).unlink(missing_ok=True)  # benchmark output only; never the original

    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
