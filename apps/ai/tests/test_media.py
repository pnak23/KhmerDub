"""Media stages against real FFmpeg on generated test movies (skipped when FFmpeg is not available)."""

import array
import math
import os
import subprocess
import wave
from pathlib import Path

import pytest

from khmerdub_ai.engine.runner import AttemptContext, Tools
from khmerdub_ai.stages import media

FFMPEG_DIR = Path(os.environ.get("KHMERDUB_TEST_FFMPEG_DIR", "D:/KhmerDub/tools/ffmpeg/bin"))
EXE = ".exe" if os.name == "nt" else ""
TOOLS = Tools(str(FFMPEG_DIR / f"ffmpeg{EXE}"), str(FFMPEG_DIR / f"ffprobe{EXE}"))
pytestmark = pytest.mark.skipif(not Path(TOOLS.ffmpeg).is_file(), reason="FFmpeg not available")

DURATION_S = 65
SR = 16000


@pytest.fixture(scope="module", params=["mp4", "mkv", "mov"])
def movie(request, tmp_path_factory) -> tuple[Path, Path]:
    """65 s movie: one video stream; audio stream 1 = 440 Hz sine at 48 kHz, stream 2 = pink noise at 44.1 kHz.
    Noise makes any time offset visible (a sine would match itself one period later)."""
    root = tmp_path_factory.mktemp(f"storage-{request.param}")
    path = root / "projects" / "p" / "original" / f"movie.{request.param}"
    path.parent.mkdir(parents=True)
    subprocess.run([TOOLS.ffmpeg, "-hide_banner", "-v", "error", "-y",
                    "-f", "lavfi", "-i", f"testsrc2=size=160x120:rate=5:duration={DURATION_S}",
                    "-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={DURATION_S}",
                    "-f", "lavfi", "-i",
                    f"anoisesrc=color=pink:sample_rate=44100:seed=9:amplitude=0.3:duration={DURATION_S}",
                    "-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-preset", "ultrafast",
                    "-c:a", "aac", "-ac", "2", "-metadata:s:a:0", "language=kor", "-disposition:a:0", "default",
                    str(path)], check=True)
    return root, path


def ctx_for(root: Path, attempt: str, stage: str, start_ms=None, end_ms=None, **inputs) -> AttemptContext:
    claim = {"attemptId": attempt, "stage": stage, "startMs": start_ms, "endMs": end_ms,
             "workDir": f"projects/p/temp/attempts/{attempt}", "input": inputs}
    ctx = AttemptContext(claim=claim, storage_root=root, tools=TOOLS)
    ctx.work_dir.mkdir(parents=True, exist_ok=True)
    return ctx


def read_samples(path: Path) -> array.array:
    with wave.open(str(path), "rb") as w:
        data = array.array("h")
        data.frombytes(w.readframes(w.getnframes()))
        return data


def test_inspect_reports_streams_and_duration(movie):
    root, path = movie
    result = media.inspect(ctx_for(root, "inspect", "INSPECT_MEDIA", originalPath=f"projects/p/original/{path.name}"))
    md = result["metadata"]
    assert abs(md["durationMs"] - DURATION_S * 1000) < 200
    assert len(md["videoStreams"]) == 1
    assert [s["index"] for s in md["audioStreams"]] == [1, 2]
    assert md["audioStreams"][0]["language"] == "kor"
    assert md["audioStreams"][0]["default"] is True


def extract(root, path, attempt, start_ms, end_ms, stream=2):
    n = (end_ms - start_ms) * SR // 1000
    ctx = ctx_for(root, attempt, "EXTRACT_AUDIO", start_ms, end_ms, originalPath=f"projects/p/original/{path.name}",
                  audioStreamIndex=stream, sampleRate=SR, expectedSamples=n)
    result = media.extract_segment(ctx)
    out = result["outputs"][0]
    assert out["info"]["samples"] == n
    assert out["path"] == f"projects/p/temp/attempts/{attempt}/segment.wav"
    return root / out["path"], out


def test_segments_are_sample_exact_deterministic_and_merge_to_the_full_track(movie):
    root, path = movie
    bounds = [(0, 25_000), (25_000, 50_000), (50_000, 65_000)]
    segments = [extract(root, path, f"seg{i}", s, e) for i, (s, e) in enumerate(bounds)]

    # Re-running a segment (as after a crash) gives a byte-identical file.
    _, again = extract(root, path, "seg1-rerun", *bounds[1])
    assert again["sha256"] == segments[1][1]["sha256"]

    total = DURATION_S * SR
    merge_ctx = ctx_for(root, "merge", "MERGE_AUDIO", 0, DURATION_S * 1000, sampleRate=SR, expectedSamples=total,
                        segmentPaths=[s[1]["path"] for s in segments])
    merged = media.merge_audio(merge_ctx)["outputs"][0]
    assert merged["info"]["samples"] == total
    merged_samples = read_samples(root / merged["path"])

    # Compare with one continuous extraction of the whole track (no seeking at all).
    whole, _ = extract(root, path, "whole", 0, DURATION_S * 1000)
    single = read_samples(whole)
    assert len(single) == len(merged_samples) == total
    assert sum(abs(s) for s in single[::50]) > 0, "the extracted stream must contain audio, not silence"

    # Inside every segment (50 ms away from its edges), find the time offset against the continuous track.
    exact = path.suffix in (".mp4", ".mov")
    for start_ms, end_ms in bounds:
        first, last = start_ms * SR // 1000 + SR // 20, end_ms * SR // 1000 - SR // 20
        lag = best_lag(merged_samples, single, first, min(first + 4000, last))
        if exact:
            assert lag == 0, f"{path.suffix} segment at {start_ms} ms is offset by {lag} samples"
            assert max(abs(merged_samples[i] - single[i]) for i in range(first, last)) == 0
        else:  # MKV timestamps have 1 ms resolution
            assert abs(lag) <= SR // 1000, f"MKV segment at {start_ms} ms is offset by {lag} samples (> 1 ms)"


def best_lag(a: array.array, b: array.array, first: int, last: int, search: int = 64) -> int:
    def cost(lag: int) -> int:
        return sum(abs(a[i] - b[i + lag]) for i in range(first, last, 2))
    return min(range(-search, search + 1), key=cost)


def test_wrong_stream_is_reported_as_an_error(movie):
    root, path = movie
    with pytest.raises(RuntimeError, match="exited with code"):
        extract(root, path, "bad-stream", 0, 5_000, stream=7)


def test_extracted_audio_is_the_selected_stream(movie):
    root, path = movie
    seg, _ = extract(root, path, "stream1", 10_000, 11_000, stream=1)
    samples = read_samples(seg)
    # Count zero crossings: 440 Hz gives ~880 per second, 880 Hz ~1760.
    crossings = sum(1 for a, b in zip(samples, samples[1:]) if (a < 0) != (b < 0))
    assert math.isclose(crossings, 880, rel_tol=0.05)
