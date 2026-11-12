"""Health checks for the AI service: Redis, FFmpeg, shared storage and Celery workers."""

from __future__ import annotations

import json
import shutil
import subprocess
import tempfile
from functools import lru_cache
from pathlib import Path
from typing import Any

from khmerdub_ai.config import Settings

UP, WARN, DOWN = "UP", "WARN", "DOWN"
WORKER_KEY_PREFIX = "khmerdub:worker:"
GB = 1024**3


def component(status: str, **details: Any) -> dict[str, Any]:
    return {"status": status, "details": details}


def check_redis(client: Any) -> dict[str, Any]:
    try:
        client.ping()
        info = client.info("server")
        return component(UP, version=info.get("redis_version"))
    except Exception as e:  # noqa: BLE001 - report any failure as DOWN
        return component(DOWN, error=f"{type(e).__name__}: {e}")


@lru_cache(maxsize=4)
def ffmpeg_version(executable: str) -> str | None:
    try:
        out = subprocess.run([executable, "-hide_banner", "-version"], capture_output=True, text=True, timeout=15)
        return out.stdout.splitlines()[0] if out.returncode == 0 and out.stdout else None
    except (OSError, subprocess.SubprocessError):
        return None


def check_ffmpeg(settings: Settings) -> dict[str, Any]:
    results = {}
    for name in ("ffmpeg", "ffprobe"):
        exe = settings.ffmpeg_executable(name)
        results[name] = {"path": exe, "version": ffmpeg_version(exe) if exe else None}
    ok = all(r["version"] for r in results.values())
    return component(UP if ok else DOWN, **results)


def check_storage(root: Path, min_free_gb: int) -> dict[str, Any]:
    if not root.is_dir():
        return component(DOWN, root=str(root), error="Storage root does not exist")
    try:
        temp = root / "temp"
        temp.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=temp, prefix=".health-", delete=True):
            pass
        usage = shutil.disk_usage(root)
    except OSError as e:
        return component(DOWN, root=str(root), writable=False, error=f"{type(e).__name__}: {e}")
    status = UP if usage.free >= min_free_gb * GB else WARN
    return component(status, root=str(root), writable=True, freeBytes=usage.free, totalBytes=usage.total,
                     minFreeBytes=min_free_gb * GB)


def check_workers(client: Any) -> dict[str, Any]:
    """Workers publish a heartbeat key with a TTL. Missing key = worker not running (or dead)."""
    try:
        workers = []
        for key in client.scan_iter(match=f"{WORKER_KEY_PREFIX}*", count=100):
            raw = client.get(key)
            if raw:
                workers.append(json.loads(raw))
    except Exception as e:  # noqa: BLE001
        return component(DOWN, error=f"{type(e).__name__}: {e}", workers=[])
    if not workers:
        return component(WARN, count=0, workers=[], warning="No Celery worker is running; processing cannot start")
    return component(UP, count=len(workers), workers=workers)


def overall(components: dict[str, dict[str, Any]]) -> str:
    if components["redis"]["status"] == DOWN:
        return DOWN
    return UP if all(c["status"] == UP for c in components.values()) else WARN
