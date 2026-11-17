"""M2 whole-stack scenarios: real API, Linux worker, Redis, PostgreSQL and FFmpeg.

Usage (stack running):  apps\\ai\\.venv\\Scripts\\python.exe tests\\e2e\\scenarios.py [--only S1,S4] [--fixtures DIR]

Every recovery scenario re-extracts the same 40-minute test movie and must produce merged audio that is
byte-identical to the clean baseline run (S1). Test projects are archived afterwards (never deleted).
"""

from __future__ import annotations

import argparse
import array
import ctypes
import json
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import wave
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

sys.path.insert(0, str(Path(__file__).parent))
import stack  # noqa: E402

MIN = 60_000
BASE: dict[str, Any] = {}
CREATED: list[str] = []


def new_extraction(fx: dict[str, Path], label: str, stream: int = 2) -> tuple[str, str]:
    pid, imp = stack.import_movie(fx["movie40"], f"E2E {label}")
    CREATED.append(pid)
    assert imp["status"] == "COMPLETED", imp["errorMessage"]
    return pid, stack.start_extraction(pid, stream, "en")


def finish_and_compare(job_id: str, timeout: float = 600) -> dict[str, Any]:
    j = stack.wait_job(job_id, {"COMPLETED", "FAILED", "CANCELLED"}, timeout)
    assert j["status"] == "COMPLETED", f"job ended {j['status']}: {j['errorMessage']}"
    a = stack.asset(j["resultAssetId"])
    assert a["sha256"] == BASE["sha256"], "merged audio differs from the clean baseline run"
    return j


def attempts(j: dict[str, Any], index: int) -> list[dict[str, Any]]:
    return stack.chunk(j, "EXTRACT_AUDIO", index)["attempts"]


# ------------------------------------------------------------------------------------------------ scenarios

def s1_baseline(fx):
    """Clean run: import, inspect, extract 4 segments + merge; sample-exact and aligned to a continuous decode."""
    pid, imp = stack.import_movie(fx["movie40"], "E2E S1 baseline")
    CREATED.append(pid)
    assert imp["status"] == "COMPLETED", imp["errorMessage"]
    probe = stack.project(pid)["original"]["metadata"]["probe"]
    assert abs(probe["durationMs"] - 40 * MIN) < 1000, probe["durationMs"]
    assert [s["index"] for s in probe["audioStreams"]] == [1, 2]
    duration = stack.project(pid)["summary"]["durationMs"]

    started = time.monotonic()
    jid = stack.start_extraction(pid, 2, "en")
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 600)
    assert j["status"] == "COMPLETED", j["errorMessage"]
    wall = time.monotonic() - started
    segments = [c for c in j["chunks"] if c["stage"] == "EXTRACT_AUDIO"]
    assert len(segments) == 4 and all(len(c["attempts"]) == 1 for c in j["chunks"])
    a = stack.asset(j["resultAssetId"])
    wav = stack.DATA / a["storagePath"]
    assert stack.wav_frames(wav) == duration * 16
    original = stack.DATA / stack.project(pid)["original"]["storagePath"]
    lags = alignment(wav, original, stream=2, at_minutes=(1, 9.99, 15, 25, 35, 39.5))
    assert all(abs(l) <= 1 for l in lags.values()), lags
    BASE.update(sha256=a["sha256"], projectId=pid)

    for fmt in ("mkv", "mov"):
        fpid, fimp = stack.import_movie(fx[fmt], f"E2E S1 {fmt}")
        CREATED.append(fpid)
        assert fimp["status"] == "COMPLETED", fimp["errorMessage"]
        fj = stack.wait_job(stack.start_extraction(fpid, 1), {"COMPLETED", "FAILED"}, 300)
        assert fj["status"] == "COMPLETED", f"{fmt}: {fj['errorMessage']}"

    seg_metrics = [c["attempts"][0]["metrics"] for c in segments]
    merge = stack.chunk(j, "MERGE_AUDIO", 0)["attempts"][0]["metrics"]
    return {"extractionWallS": round(wall, 1), "segmentFfmpegMs": [m.get("ffmpegMs") for m in seg_metrics],
            "segmentWallMs": [m.get("wallMs") for m in seg_metrics], "mergeMs": merge.get("mergeMs"),
            "mergeWallMs": merge.get("wallMs"), "lagSamplesVsContinuousDecode": lags, "mkvMov": "completed"}


def s2_child_crash(fx):
    """Kill the worker's task process mid-segment: Celery re-delivers, Spring retires the attempt, new attempt."""
    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1, "sleepBeforeS": 40}])
    pid, jid = new_extraction(fx, "S2 child crash")
    stack.wait_chunk_running(jid, 1)
    _, children = stack.worker_pids()
    killed = time.monotonic()
    stack.kill_in_worker(children[0])
    stack.wait_for(lambda: any(a["attemptNo"] == 2 and a["status"] in ("RUNNING", "SUCCEEDED")
                               for a in attempts(stack.job(jid), 1)), 120, "attempt 2 running")
    recovery = time.monotonic() - killed
    j = finish_and_compare(jid)
    first = attempts(j, 1)[0]
    assert first["status"] == "LOST" and first["endReason"] == "REDELIVERED", first
    return {"recoveryS": round(recovery, 1), "attempt1": f"{first['status']}/{first['endReason']}"}


def s3_worker_crash(fx):
    """Kill the whole worker mid-segment: Docker restarts it; Spring's lease expires; chunk reassigned."""
    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 2, "attemptNo": 1, "sleepBeforeS": 120}])
    pid, jid = new_extraction(fx, "S3 worker crash")
    stack.wait_chunk_running(jid, 2)
    restarts_before = restart_count()
    main, _ = stack.worker_pids()
    killed = time.monotonic()
    stack.kill_in_worker(main)
    stack.wait_for(lambda: attempts(stack.job(jid), 2)[0]["status"] == "LOST", 150, "lease expiry")
    lost_after = time.monotonic() - killed
    j = finish_and_compare(jid)
    first = attempts(j, 2)[0]
    assert first["endReason"] == "LEASE_EXPIRED", first
    assert restart_count() > restarts_before, "Docker should have restarted the crashed worker"
    return {"leaseExpiredAfterS": round(lost_after, 1), "totalS": round(time.monotonic() - killed, 1),
            "dockerRestarted": True}


def s4_stale_result(fx):
    """A hung worker (no heartbeats) finishes late: its result is rejected; only the new attempt publishes."""
    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1, "suppressHeartbeat": True,
                       "sleepBeforeCompleteS": 90}])
    pid, jid = new_extraction(fx, "S4 stale result")
    stack.wait_chunk_running(jid, 1)
    started = time.monotonic()
    stack.wait_for(lambda: attempts(stack.job(jid), 1)[0]["status"] == "LOST", 150, "lease expiry of hung attempt")
    lost_after = time.monotonic() - started
    j = finish_and_compare(jid, 600)
    first, second = attempts(j, 1)[:2]
    assert first["endReason"] == "LEASE_EXPIRED" and second["status"] == "SUCCEEDED"
    stale_events = [e for e in stack.events(jid) if e["type"] == "STALE_RESULT_REJECTED"]
    assert stale_events and stale_events[0]["attemptId"] == first["id"], "the late result must be rejected"
    published = stack.asset(stack.chunk(j, "EXTRACT_AUDIO", 1)["resultAssetId"])
    assert published["producedByAttemptId"] == second["id"]
    stale_file = stack.DATA / f"projects/{pid}/temp/attempts/{first['id']}/segment.wav"
    assert stale_file.exists(), "the stale output stays in its own folder, unpublished"
    assert first["id"] not in published["storagePath"]
    return {"leaseExpiredAfterS": round(lost_after, 1), "staleResultRejected": True,
            "publishedBy": "attempt 2", "staleFileLeftUnpublished": True}


def s5_api_restart(fx):
    """Restart Spring Boot while a segment is running: the worker carries on; no reassignment, no duplicate."""
    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 2, "attemptNo": 1, "sleepBeforeS": 50}])
    pid, jid = new_extraction(fx, "S5 API restart")
    stack.wait_chunk_running(jid, 2)
    started = time.monotonic()
    stack.dev("stop", "-Service", "api")
    down = time.monotonic()
    stack.dev("start", "-Service", "api")
    stack.wait_for(stack.api_up, 180, "API back")
    downtime = time.monotonic() - down
    j = finish_and_compare(jid)
    a = attempts(j, 2)
    assert len(a) == 1 and a[0]["status"] == "SUCCEEDED", a
    return {"apiDowntimeS": round(downtime, 1), "attemptsForRunningChunk": len(a),
            "totalS": round(time.monotonic() - started, 1)}


def s6_redis_restart(fx):
    """Restart Redis while a segment is running: the running work and later dispatches continue."""
    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1, "sleepBeforeS": 30}])
    pid, jid = new_extraction(fx, "S6 Redis restart")
    stack.wait_chunk_running(jid, 1)
    subprocess.run(["docker", "restart", "khmerdub-redis-1"], check=True, capture_output=True)
    j = finish_and_compare(jid)
    assert len(attempts(j, 1)) == 1
    return {"attemptsForRunningChunk": 1, "laterChunksDispatched": True}


def s7_pause_resume_cancel(fx):
    """Pause lets the running segment finish, nothing new starts; resume completes. Cancel stops running work."""
    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1, "sleepBeforeS": 20}])
    pid, jid = new_extraction(fx, "S7 pause")
    stack.wait_chunk_running(jid, 1)
    assert stack.act(jid, "pause")["status"] == "PAUSING"
    stack.wait_job(jid, {"PAUSED"}, 90)
    j = stack.job(jid)
    assert stack.chunk(j, "EXTRACT_AUDIO", 1)["status"] == "COMPLETED", "running segment finished before pausing"
    time.sleep(8)
    j = stack.job(jid)
    assert stack.chunk(j, "EXTRACT_AUDIO", 2)["attempts"] == [], "nothing new starts while paused"
    stack.act(jid, "resume")
    finish_and_compare(jid)

    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1, "sleepBeforeS": 120}])
    pid2, jid2 = new_extraction(fx, "S7 cancel")
    stack.wait_chunk_running(jid2, 1)
    asked = time.monotonic()
    stack.act(jid2, "cancel")
    j2 = stack.wait_job(jid2, {"CANCELLED"}, 60)
    latency = time.monotonic() - asked
    c1 = stack.chunk(j2, "EXTRACT_AUDIO", 1)
    assert c1["status"] == "CANCELLED" and c1["resultAssetId"] is None
    assert c1["attempts"][0]["status"] == "CANCELLED"
    assert all(c["status"] in ("COMPLETED", "CANCELLED") for c in j2["chunks"])
    return {"pause": "running segment finished, then paused; resume completed", "cancelLatencyS": round(latency, 1)}


def s8_full_restart(fx):
    """Stop everything (like a Windows restart) mid-segment, start again: the job resumes; paused stays paused."""
    stack.set_faults([{"stage": "EXTRACT_AUDIO", "chunkIndex": 2, "attemptNo": 1, "sleepBeforeS": 120}])
    pid, jid = new_extraction(fx, "S8 full restart")
    pid2, imp2 = stack.import_movie(fx["movie40"], "E2E S8 paused bystander")
    CREATED.append(pid2)
    jid2 = stack.start_extraction(pid2, 2)
    assert stack.job(jid2)["status"] == "QUEUED", "only one movie job runs at a time"
    stack.act(jid2, "pause")
    stack.wait_chunk_running(jid, 2)
    stack.dev("stop", "-All")
    time.sleep(5)
    started = time.monotonic()
    stack.dev("start", diagnostics=True)
    stack.wait_for(stack.api_up, 240, "API back")
    j = finish_and_compare(jid, 900)
    recovery = time.monotonic() - started
    first = attempts(j, 2)[0]
    assert first["status"] == "LOST", first
    assert stack.job(jid2)["status"] == "PAUSED", "a paused job stays paused across restarts"
    stack.act(jid2, "resume")
    finish_and_compare(jid2)
    return {"timeFromStartToCompletedS": round(recovery, 1), "interruptedAttempt": first["endReason"],
            "pausedJobStayedPaused": True}


def s9_invalid_inputs(fx):
    """Wrong content, no audio, too long, unsupported type."""
    pid = stack.create_project("E2E S9 invalid")
    CREATED.append(pid)
    fake = stack.upload(pid, fx["fake"], name="not-a-movie.mp4")
    assert fake.status_code == 415, fake.text
    avi = stack.http.post(f"/api/projects/{pid}/upload/check", json={"fileName": "clip.avi", "sizeBytes": 10})
    assert avi.status_code == 415
    pid2, imp2 = stack.import_movie(fx["noaudio"], "E2E S9 no audio")
    CREATED.append(pid2)
    assert imp2["status"] == "FAILED" and "no audio stream" in imp2["errorMessage"], imp2["errorMessage"]
    pid3, imp3 = stack.import_movie(fx["long181"], "E2E S9 too long")
    CREATED.append(pid3)
    assert imp3["status"] == "FAILED" and "180 minutes" in imp3["errorMessage"], imp3["errorMessage"]
    return {"fakeFile": fake.json().get("detail"), "noAudio": imp2["errorMessage"], "tooLong": imp3["errorMessage"]}


def s10_proxy_buffering(fx):
    """Does the Nuxt proxy buffer request bodies? Send 300 MB through it and through Spring directly."""
    pid = stack.create_project("E2E S10 proxy check")
    CREATED.append(pid)
    web_port = urlparse(stack.WEB).port
    node_pid = int(subprocess.run(["powershell", "-NoProfile", "-Command",
                                   f"(Get-NetTCPConnection -State Listen -LocalPort {web_port}).OwningProcess"],
                                  capture_output=True, text=True).stdout.split()[0])
    body = fx["fake300"]  # MKV magic under a .mp4 name: Spring rejects it after 64 bytes, nothing is stored
    results = {}
    for label, base in (("throughNuxtProxy", stack.WEB), ("directToSpring", stack.API)):
        baseline = working_set(node_pid)
        peak = [baseline]
        done = threading.Event()

        def sample():
            while not done.is_set():
                peak[0] = max(peak[0], working_set(node_pid))
                time.sleep(0.05)

        t = threading.Thread(target=sample)
        t.start()
        try:
            r = stack.upload(pid, body, base=base, name="probe.mp4")
            status = r.status_code
        except Exception as e:  # noqa: BLE001 - the server may close early
            status = type(e).__name__
        done.set()
        t.join()
        results[label] = {"status": status, "nodeMemoryGrowthMB": round((peak[0] - baseline) / 2**20)}
    return results


# ------------------------------------------------------------------------------------------------- helpers

def restart_count() -> int:
    out = subprocess.run(["docker", "inspect", stack.WORKER, "--format", "{{.RestartCount}}"], capture_output=True,
                         text=True, check=True)
    return int(out.stdout.strip())


def working_set(pid: int) -> int:
    class Counters(ctypes.Structure):
        _fields_ = [("cb", ctypes.c_ulong), ("PageFaultCount", ctypes.c_ulong), ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t), ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPagedPoolUsage", ctypes.c_size_t), ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaNonPagedPoolUsage", ctypes.c_size_t), ("PagefileUsage", ctypes.c_size_t),
                    ("PeakPagefileUsage", ctypes.c_size_t)]
    handle = ctypes.windll.kernel32.OpenProcess(0x1000 | 0x0010, False, pid)
    c = Counters()
    c.cb = ctypes.sizeof(c)
    ctypes.windll.psapi.GetProcessMemoryInfo(handle, ctypes.byref(c), c.cb)
    ctypes.windll.kernel32.CloseHandle(handle)
    return c.WorkingSetSize


def alignment(wav: Path, original: Path, stream: int, at_minutes) -> dict[str, int]:
    """Lag (in 16 kHz samples) between the pipeline's audio and an independent continuous decode with the
    Windows FFmpeg build, at several points of the movie. 0 = perfectly aligned."""
    with tempfile.TemporaryDirectory() as tmp:
        ref = Path(tmp) / "ref.raw"
        subprocess.run([stack.FFMPEG, "-hide_banner", "-v", "error", "-y", "-i", str(original), "-map", f"0:{stream}",
                        "-af", "aresample=16000", "-ac", "1", "-f", "s16le", str(ref)], check=True)
        reference = array.array("h")
        reference.frombytes(ref.read_bytes())
    with wave.open(str(wav), "rb") as w:
        ours = array.array("h")
        ours.frombytes(w.readframes(w.getnframes()))
    lags = {}
    for m in at_minutes:
        first = int(m * 60 * 16000)
        lags[f"{m}min"] = min(range(-48, 49), key=lambda lag: sum(
            abs(ours[i] - reference[i + lag]) for i in range(first, first + 3000, 3)))
    return lags


def fixtures(directory: Path) -> dict[str, Path]:
    directory.mkdir(parents=True, exist_ok=True)
    fake300 = directory / "fake300.bin"
    if not fake300.exists():
        with open(fake300, "wb") as f:
            f.write(bytes([0x1A, 0x45, 0xDF, 0xA3]) + b"\0" * 60)
            f.write(b"\0" * (300 * 2**20))
    fake = directory / "fake.mp4"
    fake.write_text("this is not a movie\n" * 100)
    return {
        "movie40": stack.make_movie(directory / "movie40.mp4", 40),
        "mkv": stack.make_movie(directory / "short.mkv", 3),
        "mov": stack.make_movie(directory / "short.mov", 3),
        "noaudio": stack.make_movie(directory / "noaudio.mp4", 1, audio=False),
        "long181": make_long(directory / "long181.mp4"),
        "fake": fake,
        "fake300": fake300,
    }


def make_long(path: Path) -> Path:
    if not path.exists():
        subprocess.run([stack.FFMPEG, "-hide_banner", "-v", "error", "-y",
                        "-f", "lavfi", "-i", "color=c=black:size=16x16:rate=1:duration=10860",
                        "-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono:duration=10860",
                        "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac", "-b:a", "8k", str(path)], check=True)
    return path


SCENARIOS = {"S1": s1_baseline, "S2": s2_child_crash, "S3": s3_worker_crash, "S4": s4_stale_result,
             "S5": s5_api_restart, "S6": s6_redis_restart, "S7": s7_pause_resume_cancel, "S8": s8_full_restart,
             "S9": s9_invalid_inputs, "S10": s10_proxy_buffering}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default=",".join(SCENARIOS))
    ap.add_argument("--fixtures", default=str(Path(tempfile.gettempdir()) / "khmerdub-e2e-fixtures"))
    args = ap.parse_args()
    selected = [s.strip() for s in args.only.split(",") if s.strip()]
    fx = fixtures(Path(args.fixtures))
    print(f"fixtures ready in {args.fixtures}", flush=True)
    stack.recreate_worker(diagnostics=True)
    results: dict[str, Any] = {}
    try:
        if "S1" not in selected:
            selected.insert(0, "S1")  # the baseline checksum is needed by the others
        for name in selected:
            stack.clear_faults()
            began = time.monotonic()
            try:
                detail = SCENARIOS[name](fx)
                results[name] = {"result": "PASS", "seconds": round(time.monotonic() - began), **detail}
            except Exception as e:  # noqa: BLE001
                results[name] = {"result": "FAIL", "seconds": round(time.monotonic() - began),
                                 "error": f"{type(e).__name__}: {e}", "trace": traceback.format_exc()[-1500:]}
            print(json.dumps({name: results[name]}), flush=True)
    finally:
        stack.clear_faults()
        if stack.api_up():
            for pid in CREATED:
                stack.archive(pid)
        stack.recreate_worker(diagnostics=False)
    passed = sum(1 for r in results.values() if r["result"] == "PASS")
    print(f"SUMMARY {passed}/{len(results)} passed", flush=True)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
