"""Diagnostic tasks: worker round-trip and a controllable long task for recovery testing."""

from __future__ import annotations

import json
import os
import re
import socket
import sys
import time
from datetime import UTC, datetime
from typing import Any

from khmerdub_ai.celery_app import app
from khmerdub_ai.config import get_settings
from khmerdub_ai.storage import atomic_write_bytes, resolve_in_storage

_MARKER = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def _now() -> str:
    return datetime.now(UTC).isoformat()


@app.task(name="khmerdub.system.ping", bind=True)
def ping(self: Any, probe: bool = True) -> dict[str, Any]:
    """Prove the worker runs and can write to the shared storage root (atomic write + rename)."""
    info: dict[str, Any] = {
        "taskId": self.request.id,
        "worker": self.request.hostname or socket.gethostname(),
        "os": sys.platform,
        "pid": os.getpid(),
        "time": _now(),
    }
    if probe:
        rel = f"temp/diagnostics/ping-{self.request.id}.json"
        path = resolve_in_storage(get_settings().storage_root, rel)
        atomic_write_bytes(path, json.dumps(info).encode("utf-8"))
        info["probeFile"] = rel
    return info


@app.task(name="khmerdub.system.sleep", bind=True)
def sleep_task(self: Any, seconds: float, marker: str) -> dict[str, Any]:
    """Long-running diagnostic task. Appends start/finish lines to temp/diagnostics/<marker>.log."""
    if not _MARKER.match(marker):
        raise ValueError("marker must match [A-Za-z0-9_-]{1,64}")
    log = resolve_in_storage(get_settings().storage_root, f"temp/diagnostics/{marker}.log")
    log.parent.mkdir(parents=True, exist_ok=True)
    redelivered = bool((self.request.delivery_info or {}).get("redelivered"))

    def append(event: str) -> None:
        with open(log, "a", encoding="utf-8") as f:
            f.write(json.dumps({"event": event, "time": _now(), "pid": os.getpid(), "os": sys.platform,
                                "taskId": self.request.id, "redelivered": redelivered}) + "\n")

    append("start")
    time.sleep(seconds)
    append("finish")
    return {"marker": marker, "seconds": seconds, "pid": os.getpid()}
