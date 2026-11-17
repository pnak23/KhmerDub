"""Helpers for whole-stack tests: talk to the real API, control services, inject faults, make fixtures.

Requires the running stack (scripts/dev.ps1 start). Run with the apps/ai venv Python.

The defaults target the normal local stack. An isolated test stack is selected with environment variables
(KHMERDUB_API, KHMERDUB_WEB, KHMERDUB_STORAGE_ROOT, KHMERDUB_WORKER_CONTAINER, KHMERDUB_E2E_COMPOSE as a JSON list
of docker arguments, KHMERDUB_E2E_DEV_SCRIPT, KHMERDUB_E2E_PG_CONTAINER). With KHMERDUB_E2E_ISOLATED=1 every one of them must be set and point
away from the normal stack, otherwise nothing runs.
"""

from __future__ import annotations

import json
import os
import subprocess
import time
import wave
from collections.abc import Callable
from pathlib import Path
from typing import Any, TypeVar
from urllib.parse import quote

import httpx

REPO = Path(__file__).resolve().parents[2]
API = os.environ.get("KHMERDUB_API", "http://127.0.0.1:8180")
WEB = os.environ.get("KHMERDUB_WEB", "http://localhost:3000")
DATA = Path(os.environ.get("KHMERDUB_STORAGE_ROOT", "D:/KhmerDub/data"))
FFMPEG = os.environ.get("KHMERDUB_FFMPEG", "D:/KhmerDub/tools/ffmpeg/bin/ffmpeg.exe")
WORKER = os.environ.get("KHMERDUB_WORKER_CONTAINER", "khmerdub-worker-cpu-1")
COMPOSE = (json.loads(os.environ["KHMERDUB_E2E_COMPOSE"]) if os.environ.get("KHMERDUB_E2E_COMPOSE") else
           ["docker", "compose", "-f", str(REPO / "infra/docker-compose.yml"), "--env-file", str(REPO / ".env"),
            "--profile", "linux-worker"])
DEV_SCRIPT = Path(os.environ.get("KHMERDUB_E2E_DEV_SCRIPT", str(REPO / "scripts/dev.ps1")))
PG_CONTAINER = os.environ.get("KHMERDUB_E2E_PG_CONTAINER", "khmerdub-postgres-1")
FAULTS = DATA / "temp" / "diagnostics" / "faults.json"


def _require_isolated() -> None:
    """KHMERDUB_E2E_ISOLATED=1: refuse to start unless every target is overridden away from the normal stack."""
    names = ("KHMERDUB_API", "KHMERDUB_WEB", "KHMERDUB_STORAGE_ROOT", "KHMERDUB_WORKER_CONTAINER", "KHMERDUB_E2E_COMPOSE",
             "KHMERDUB_E2E_DEV_SCRIPT", "KHMERDUB_E2E_PG_CONTAINER")
    missing = [n for n in names if not os.environ.get(n)]
    normal = [what for what, bad in (
        ("API port 8180", ":8180" in API), ("web port 3000", ":3000" in WEB),
        ("normal storage", DATA.resolve() == Path("D:/KhmerDub/data").resolve()),
        ("normal worker", WORKER == "khmerdub-worker-cpu-1"), ("normal database", PG_CONTAINER == "khmerdub-postgres-1"),
        ("normal compose", str(REPO / "infra") in " ".join(COMPOSE)),
        ("normal dev.ps1", DEV_SCRIPT.resolve() == (REPO / "scripts/dev.ps1").resolve())) if bad]
    if missing or normal:
        raise SystemExit(f"KHMERDUB_E2E_ISOLATED=1 but not isolated: missing {missing}, normal {normal}")


if os.environ.get("KHMERDUB_E2E_ISOLATED") == "1":
    _require_isolated()

http = httpx.Client(base_url=API, timeout=60)
T = TypeVar("T")


# ------------------------------------------------------------------------------------------------- waiting

def wait_for(check: Callable[[], T], timeout: float, what: str, interval: float = 0.5) -> T:
    deadline = time.monotonic() + timeout
    last: Any = None
    while time.monotonic() < deadline:
        try:
            last = check()
            if last:
                return last
        except (httpx.HTTPError, OSError) as e:
            last = e
        time.sleep(interval)
    raise TimeoutError(f"Timed out after {timeout:.0f}s waiting for {what} (last: {last!r})")


# ------------------------------------------------------------------------------------------------- API

def api_up() -> bool:
    try:
        return http.get("/api/health", timeout=5).status_code == 200
    except httpx.HTTPError:
        return False


def worker_ready() -> bool:
    h = http.get("/api/health", timeout=10).json()
    workers = h["components"]["aiService"]["details"].get("components", {}).get("workers", {})
    return bool(workers.get("details", {}).get("workers"))


def create_project(name: str, language: str = "auto") -> str:
    r = http.post("/api/projects", json={"name": name, "sourceLanguage": language})
    r.raise_for_status()
    return r.json()["summary"]["id"]


def upload(project_id: str, path: Path, base: str = API, name: str | None = None) -> httpx.Response:
    size = path.stat().st_size

    def chunks():
        with open(path, "rb") as f:
            while block := f.read(1 << 20):
                yield block

    return httpx.post(f"{base}/api/projects/{project_id}/upload", content=chunks(), timeout=3600, headers={
        "Content-Type": "application/octet-stream", "Content-Length": str(size),
        "X-File-Name": quote(name or path.name), "Origin": WEB})


def job(job_id: str) -> dict[str, Any]:
    r = http.get(f"/api/jobs/{job_id}")
    r.raise_for_status()
    return r.json()


def events(job_id: str, limit: int = 300) -> list[dict[str, Any]]:
    return http.get(f"/api/jobs/{job_id}/events", params={"limit": limit}).json()


def project(project_id: str) -> dict[str, Any]:
    return http.get(f"/api/projects/{project_id}").json()


def asset(asset_id: str) -> dict[str, Any]:
    return http.get(f"/api/assets/{asset_id}").json()


def act(job_id: str, action: str) -> dict[str, Any]:
    r = http.post(f"/api/jobs/{job_id}/{action}")
    r.raise_for_status()
    return r.json()


def wait_job(job_id: str, statuses: set[str], timeout: float) -> dict[str, Any]:
    return wait_for(lambda: (j := job(job_id))["status"] in statuses and j, timeout, f"job {statuses}")


def import_movie(path: Path, name: str) -> tuple[str, dict[str, Any]]:
    pid = create_project(name)
    r = upload(pid, path)
    r.raise_for_status()
    imp = wait_job(r.json()["importJob"]["id"], {"COMPLETED", "FAILED"}, 300)
    return pid, imp


def start_extraction(project_id: str, stream: int, language: str = "auto") -> str:
    r = http.post(f"/api/projects/{project_id}/extract-audio", json={"audioStreamIndex": stream, "sourceLanguage": language})
    r.raise_for_status()
    return r.json()["id"]


def chunk(j: dict[str, Any], stage: str, index: int) -> dict[str, Any]:
    return next(c for c in j["chunks"] if c["stage"] == stage and c["index"] == index)


def wait_chunk_running(job_id: str, index: int, attempt_no: int = 1, timeout: float = 120) -> dict[str, Any]:
    def running():
        c = next((c for c in job(job_id)["chunks"] if c["stage"] == "EXTRACT_AUDIO" and c["index"] == index), None)
        return c and c["status"] == "RUNNING" and any(a["attemptNo"] == attempt_no and a["status"] == "RUNNING"
                                                         for a in c["attempts"]) and c
    return wait_for(running, timeout, f"chunk {index} attempt {attempt_no} running")


def archive(project_id: str) -> None:
    http.post(f"/api/projects/{project_id}/archive")


# ------------------------------------------------------------------------------------------------- faults

def set_faults(rules: list[dict[str, Any]]) -> None:
    FAULTS.parent.mkdir(parents=True, exist_ok=True)
    FAULTS.write_text(json.dumps(rules), encoding="utf-8")


def clear_faults() -> None:
    FAULTS.unlink(missing_ok=True)


def recreate_worker(diagnostics: bool) -> None:
    env = dict(os.environ, KHMERDUB_DIAGNOSTICS="true" if diagnostics else "false")
    subprocess.run([*COMPOSE, "up", "-d", "--force-recreate", "worker-cpu"], env=env, check=True,
                   capture_output=True)
    wait_for(lambda: docker_env("KHMERDUB_DIAGNOSTICS") == ("true" if diagnostics else "false"), 30, "worker env")
    wait_for(worker_ready, 60, "worker heartbeat")


def docker_env(name: str) -> str:
    out = subprocess.run(["docker", "exec", WORKER, "sh", "-c", f"echo ${name}"], capture_output=True, text=True)
    return out.stdout.strip()


# ------------------------------------------------------------------------------------------------- processes

_PROCS = ("import os\n"
          "procs = {}\n"
          "for p in os.listdir('/proc'):\n"
          "    if not p.isdigit(): continue\n"
          "    try:\n"
          "        cmd = open(f'/proc/{p}/cmdline','rb').read().replace(b'\\0', b' ').decode()\n"
          "        ppid = int([l for l in open(f'/proc/{p}/status') if l.startswith('PPid:')][0].split()[1])\n"
          "    except OSError: continue\n"
          "    if 'celery' in cmd: procs[int(p)] = ppid\n"
          "main = [p for p, pp in procs.items() if pp == 1]\n"
          "print(main[0] if main else -1, ' '.join(str(p) for p, pp in procs.items() if main and pp == main[0]))\n")


def worker_pids() -> tuple[int, list[int]]:
    out = subprocess.run(["docker", "exec", WORKER, "python", "-c", _PROCS], capture_output=True, text=True,
                         check=True).stdout.split()
    return int(out[0]), [int(p) for p in out[1:]]


def kill_in_worker(pid: int) -> None:
    """SIGKILL a process inside the worker container. Killing the container's main process stops the container,
    and Docker then kills this exec session too: exit status 137 is the expected outcome in that case."""
    r = subprocess.run(["docker", "exec", WORKER, "python", "-c", f"import os,signal; os.kill({pid}, signal.SIGKILL)"])
    if r.returncode not in (0, 137):
        raise subprocess.CalledProcessError(r.returncode, r.args)


def dev(*args: str, diagnostics: bool | None = None) -> None:
    """Run scripts/dev.ps1. `diagnostics` keeps the worker's fault-injection switch as it is across a restart."""
    env = dict(os.environ)
    if diagnostics is not None:
        env["KHMERDUB_DIAGNOSTICS"] = "true" if diagnostics else "false"
    # Output goes to DEVNULL, not a pipe: on Windows the services dev.ps1 starts inherit the pipe handles and
    # keep them open, so capturing output would wait forever for an end-of-file that never comes.
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                    str(DEV_SCRIPT), *args], check=True, env=env,
                   stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


# ------------------------------------------------------------------------------------------------- fixtures

def make_movie(path: Path, minutes: float, *, audio: bool = True, container_args: tuple[str, ...] = ()) -> Path:
    """Tiny-video test movie. Audio stream 1: 440 Hz sine 48 kHz (default, Korean); stream 2: pink noise
    44.1 kHz (English). Noise makes timing errors measurable."""
    if path.exists():
        return path
    seconds = round(minutes * 60, 3)
    cmd = [FFMPEG, "-hide_banner", "-v", "error", "-y",
           "-f", "lavfi", "-i", f"testsrc2=size=160x90:rate=1:duration={seconds}"]
    maps = ["-map", "0"]
    if audio:
        cmd += ["-f", "lavfi", "-i", f"sine=frequency=440:sample_rate=48000:duration={seconds}",
                "-f", "lavfi", "-i", f"anoisesrc=color=pink:sample_rate=44100:seed=9:amplitude=0.3:duration={seconds}"]
        maps += ["-map", "1", "-map", "2", "-c:a", "aac", "-b:a", "96k", "-ac", "2",
                 "-metadata:s:a:0", "language=kor", "-metadata:s:a:1", "language=eng", "-disposition:a:0", "default"]
    subprocess.run([*cmd, *maps, "-c:v", "libx264", "-preset", "ultrafast", "-g", "60", *container_args, str(path)],
                   check=True)
    return path


def wav_frames(path: Path) -> int:
    with wave.open(str(path), "rb") as w:
        return w.getnframes()
