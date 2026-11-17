"""M3b-1 whole-stack scenarios (S11-S17) on a real speech clip. Requires the running stack (scripts/dev.ps1 start).

Run with the apps/ai venv Python:  python tests/e2e/m3b_scenarios.py --clip <movie> [--only S11,S12]
Keep-awake evidence comes from `powercfg /requests` (no admin rights needed).
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import traceback
from pathlib import Path
from typing import Any

import stack

CREATED: list[str] = []
ACTIVE_STATUSES = {"QUEUED", "RUNNING", "PAUSING", "PAUSED", "CANCELLING"}


# ------------------------------------------------------------------------------------------------- helpers

def keep_awake_held() -> bool:
    out = subprocess.run(["powercfg", "/requests"], capture_output=True, text=True).stdout
    section, held = None, False
    for line in out.splitlines():
        s = line.strip()
        if s.endswith(":") and s[:-1].isupper():
            section = s[:-1]
        elif section == "SYSTEM" and "python" in s.lower():
            held = True
    return held


def wait_keep_awake(expected: bool, timeout: float) -> float:
    began = time.monotonic()
    stack.wait_for(lambda: keep_awake_held() == expected, timeout, f"keep-awake held={expected}", interval=2)
    return round(time.monotonic() - began, 1)


def audio_ready_project(clip: Path, label: str) -> str:
    pid, imp = stack.import_movie(clip, f"E2E {label}")
    CREATED.append(pid)
    assert imp["status"] == "COMPLETED", imp
    j = stack.wait_job(stack.start_extraction(pid, 1), {"COMPLETED", "FAILED"}, 600)
    assert j["status"] == "COMPLETED", j
    return pid


def transcribe(pid: str, mode: str = "FAST", confirm: bool = False) -> str:
    r = stack.http.post(f"/api/projects/{pid}/transcribe", json={"mode": mode, "language": "auto",
                                                                 "confirmReprocess": confirm})
    r.raise_for_status()
    return r.json()["id"]


def wait_stage_running(jid: str, stage: str, timeout: float = 900) -> dict[str, Any]:
    def running():
        j = stack.job(jid)
        for c in j["chunks"]:
            if c["stage"] == stage and c["status"] == "RUNNING" and any(a["status"] == "RUNNING" for a in c["attempts"]):
                return c
        if j["status"] not in ACTIVE_STATUSES:
            raise AssertionError(f"job ended ({j['status']}) before {stage} ran: {j.get('errorMessage')}")
        return None
    return stack.wait_for(running, timeout, f"{stage} running", interval=2)


def revisions(pid: str) -> list[dict[str, Any]]:
    return stack.http.get(f"/api/projects/{pid}/dialogue-revisions").json()


def active_export(pid: str) -> str:
    """Everything a user could have reviewed in the ACTIVE revision, as stable JSON."""
    page = stack.http.get(f"/api/projects/{pid}/dialogue", params={"limit": 1000}).json()
    spk = stack.http.get(f"/api/projects/{pid}/speakers").json()
    return json.dumps({"lines": page["lines"], "speakers": spk}, sort_keys=True, ensure_ascii=False)


def no_private_data_in(*bodies: str) -> None:
    for b in bodies:
        low = b.lower()
        assert "fingerprints" not in low and ".npy" not in low and "embedding" not in low, "private data leaked"


# ------------------------------------------------------------------------------------------------- scenarios

def s11_full_run(clip: Path) -> dict[str, Any]:
    """Quality run on real speech: revision ACTIVE, keep-awake during the run and released after, privacy."""
    pid = audio_ready_project(clip, "S11 full run")
    jid = transcribe(pid, "QUALITY")
    held_after = wait_keep_awake(True, 120)
    began = time.monotonic()
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 3600)
    assert j["status"] == "COMPLETED", j.get("errorMessage")
    released_after = wait_keep_awake(False, 60)
    revs = revisions(pid)
    assert revs and revs[0]["status"] == "ACTIVE", revs
    job_json = stack.http.get(f"/api/jobs/{jid}").text
    spk = stack.http.get(f"/api/projects/{pid}/speakers")
    dia = stack.http.get(f"/api/projects/{pid}/dialogue", params={"limit": 1000})
    no_private_data_in(job_json, spk.text, dia.text)
    embedding_ids = [a for a in _assets_of(pid, "SPEAKER_EMBEDDING")]
    statuses = {stack.http.get(f"/api/assets/{a}").status_code for a in embedding_ids}
    assert embedding_ids and statuses == {404}, statuses
    return {"projectId": pid, "jobId": jid, "transcribeS": round(time.monotonic() - began),
            "keepAwakeAcquiredAfterS": held_after, "keepAwakeReleasedAfterCompletionS": released_after,
            "lines": revs[0]["summary"].get("lines"), "speakers": revs[0]["summary"].get("speakers"),
            "duplicateWords": revs[0]["summary"].get("duplicateWords"),
            "uncoveredSpeechRegions": revs[0]["summary"].get("uncoveredSpeechRegions"),
            "embeddingsServed": False}


def _assets_of(pid: str, kind: str) -> list[str]:
    out = subprocess.run(["docker", "exec", stack.PG_CONTAINER, "sh", "-c",
                          f"psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -At -c \"select id from media_assets "
                          f"where project_id = '{pid}' and asset_type = '{kind}'\""], capture_output=True, text=True)
    return [x for x in out.stdout.split() if x]


def s12_pause_resume(clip: Path) -> dict[str, Any]:
    pid = audio_ready_project(clip, "S12 pause resume")
    jid = transcribe(pid)
    wait_stage_running(jid, "TRANSCRIBE")
    stack.act(jid, "pause")
    paused = stack.wait_job(jid, {"PAUSED"}, 900)
    released = wait_keep_awake(False, 60)
    time.sleep(20)
    assert stack.job(jid)["status"] == "PAUSED" and not keep_awake_held()
    stack.act(jid, "resume")
    reacquired = wait_keep_awake(True, 120)
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 3600)
    assert j["status"] == "COMPLETED", j.get("errorMessage")
    return {"pausedAtStage": paused["currentStage"], "keepAwakeReleasedAfterPausedS": released,
            "keepAwakeReacquiredAfterResumeS": reacquired, "completed": True}


def s13_worker_crash_in_diarize(clip: Path) -> dict[str, Any]:
    pid = audio_ready_project(clip, "S13 worker crash")
    jid = transcribe(pid)
    chunk = wait_stage_running(jid, "DIARIZE")
    time.sleep(20)
    main, _ = stack.worker_pids()
    killed = time.monotonic()
    stack.kill_in_worker(main)
    stack.wait_for(lambda: any(a["status"] == "LOST" for c in stack.job(jid)["chunks"] if c["id"] == chunk["id"]
                               for a in c["attempts"]), 180, "lease expiry")
    lost_after = round(time.monotonic() - killed, 1)
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 3600)
    assert j["status"] == "COMPLETED", j.get("errorMessage")
    c = next(c for c in j["chunks"] if c["id"] == chunk["id"])
    return {"leaseExpiredAfterS": lost_after, "attemptsOnCrashedChunk": [f"{a['status']}/{a.get('endReason')}" for a in c["attempts"]],
            "totalAfterKillS": round(time.monotonic() - killed), "completed": True}


def s14_api_restart(clip: Path) -> dict[str, Any]:
    pid = audio_ready_project(clip, "S14 api restart")
    jid = transcribe(pid)
    chunk = wait_stage_running(jid, "TRANSCRIBE")
    down = time.monotonic()
    stack.dev("stop", "-Service", "api")
    stack.dev("start", "-Service", "api")
    stack.wait_for(stack.api_up, 300, "api up")
    downtime = round(time.monotonic() - down, 1)
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 3600)
    assert j["status"] == "COMPLETED", j.get("errorMessage")
    c = next(c for c in j["chunks"] if c["id"] == chunk["id"])
    return {"apiDowntimeS": downtime, "attemptsForRunningChunk": len(c["attempts"]), "completed": True}


def s15_cancel_reprocess_in_diarize(clip: Path, pid: str) -> dict[str, Any]:
    before = active_export(pid)
    jid = transcribe(pid, confirm=True)
    wait_stage_running(jid, "DIARIZE")
    asked = time.monotonic()
    stack.act(jid, "cancel")
    stack.wait_job(jid, {"CANCELLED"}, 300)
    cancel_s = round(time.monotonic() - asked, 1)
    released = wait_keep_awake(False, 60)
    assert active_export(pid) == before, "active dialogue changed"
    return {"cancelLatencyS": cancel_s, "keepAwakeReleasedAfterCancelledS": released, "activeDialogueUnchanged": True}


def s16_failed_reprocess(clip: Path, pid: str) -> dict[str, Any]:
    before = active_export(pid)
    r = stack.http.post(f"/api/projects/{pid}/transcribe", json={"mode": "FAST", "language": "auto", "confirmReprocess": False})
    assert r.status_code == 409 and "REPROCESS_CONFIRMATION_REQUIRED" in r.text, r.text
    stack.set_faults([{"stage": "DIARIZE", "fail": "invalid"}])
    try:
        jid = transcribe(pid, confirm=True)
        j = stack.wait_job(jid, {"FAILED", "COMPLETED"}, 3600)
    finally:
        stack.clear_faults()
    assert j["status"] == "FAILED", j["status"]
    assert active_export(pid) == before, "active dialogue changed after a failed reprocess"
    backup_id = j["parameters"].get("backupAssetId")
    b = stack.asset(backup_id)
    path = stack.DATA / b["storagePath"]
    import hashlib
    assert hashlib.sha256(path.read_bytes()).hexdigest() == b["sha256"]
    return {"withoutConfirmation": "409 REPROCESS_CONFIRMATION_REQUIRED", "failedJob": j["errorMessage"][:120],
            "activeDialogueByteIdentical": True, "verifiedBackup": b["storagePath"].split("/")[-1],
            "revisions": [f"{x['revisionNo']}:{x['status']}" for x in revisions(pid)]}


def s17_stale_worker_releases_keep_awake(clip: Path) -> dict[str, Any]:
    pid = audio_ready_project(clip, "S17 stale worker")
    jid = transcribe(pid)
    wait_stage_running(jid, "TRANSCRIBE")
    assert keep_awake_held()
    stopped = time.monotonic()
    subprocess.run(["docker", "stop", "-t", "2", stack.WORKER], check=True, capture_output=True)
    released = wait_keep_awake(False, 330)
    released_after = round(time.monotonic() - stopped, 1)
    assert stack.job(jid)["status"] == "RUNNING"
    subprocess.run(["docker", "start", stack.WORKER], check=True, capture_output=True)
    reacquired = wait_keep_awake(True, 300)  # seconds from `docker start` until the request is back
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 3600)
    assert j["status"] == "COMPLETED", j.get("errorMessage")
    return {"keepAwakeReleasedAfterWorkerStopS": released_after, "expectedRule": "last heartbeat + 60 s lease + 120 s margin",
            "keepAwakeReacquiredAfterWorkerStartS": reacquired, "completed": True}


SCENARIOS = ["S11", "S12", "S13", "S14", "S15", "S16", "S17"]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--clip", required=True)
    ap.add_argument("--only", default=",".join(SCENARIOS))
    args = ap.parse_args()
    clip = Path(args.clip)
    selected = [s.strip() for s in args.only.split(",") if s.strip()]
    # Keep an unattended PC awake for the harness itself (see hold_awake.ps1); it is not a python.exe request.
    stop_file = Path(__file__).with_name(".hold-awake.stop")
    stop_file.unlink(missing_ok=True)
    holder = subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                               str(Path(__file__).with_name("hold_awake.ps1")), "-StopFile", str(stop_file)])
    stack.recreate_worker(diagnostics=True)
    results: dict[str, Any] = {}
    s11_project = None
    try:
        for name in selected:
            stack.clear_faults()
            began = time.monotonic()
            try:
                if name == "S11":
                    detail = s11_full_run(clip)
                    s11_project = detail["projectId"]
                elif name in ("S15", "S16"):
                    if s11_project is None:
                        raise RuntimeError("needs S11's project")
                    detail = (s15_cancel_reprocess_in_diarize if name == "S15" else s16_failed_reprocess)(clip, s11_project)
                else:
                    detail = {"S12": s12_pause_resume, "S13": s13_worker_crash_in_diarize, "S14": s14_api_restart,
                              "S17": s17_stale_worker_releases_keep_awake}[name](clip)
                results[name] = {"result": "PASS", "seconds": round(time.monotonic() - began), **detail}
            except Exception as e:  # noqa: BLE001
                results[name] = {"result": "FAIL", "seconds": round(time.monotonic() - began),
                                 "error": f"{type(e).__name__}: {e}", "trace": traceback.format_exc()[-1500:]}
            print(json.dumps({name: results[name]}, ensure_ascii=False), flush=True)
    finally:
        stack.clear_faults()
        subprocess.run(["docker", "start", stack.WORKER], capture_output=True)
        if stack.api_up():
            for pid in CREATED:
                stack.archive(pid)
        stack.recreate_worker(diagnostics=False)
        stop_file.touch()
        try:
            holder.wait(timeout=15)
        except subprocess.TimeoutExpired:
            holder.kill()
        stop_file.unlink(missing_ok=True)
    passed = sum(1 for r in results.values() if r["result"] == "PASS")
    print(f"SUMMARY {passed}/{len(results)} passed", flush=True)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
