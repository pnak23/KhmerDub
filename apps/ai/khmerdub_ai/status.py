"""Worker self-report for the System page.

When a worker starts, it runs the quick speech-stack check (package versions, Silero VAD, model sizes; no hashing)
and the network isolation check once, in a background thread, and writes a sanitised summary to
{storage_root}/system/worker-status.json. Spring reads that file and copies out only allow-listed fields.

The summary holds booleans and short status words only: never paths, tokens, hostnames of the host, or anything
from a project. Failures are reported as "unavailable" and never stop the worker.
"""

from __future__ import annotations

import json
import os
import platform
import threading
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from celery.signals import worker_ready

from khmerdub_ai.config import get_settings

STATUS_RELATIVE = Path("system") / "worker-status.json"
OFFLINE_FLAGS = ("HF_HUB_OFFLINE", "HF_HUB_DISABLE_TELEMETRY", "PYANNOTE_METRICS_ENABLED")
_SAFE_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 ._:()+-")


def _word(value: Any, limit: int = 60) -> str | None:
    """A short status word from a safe character set, else None (so nothing unexpected can leak)."""
    if not isinstance(value, str):
        return None
    text = value[:limit]
    return text if all(c in _SAFE_CHARS for c in text) else None


def sanitize(ml: dict[str, Any] | None, net: dict[str, Any] | None) -> dict[str, Any]:
    out: dict[str, Any] = {"reportedAt": datetime.now(UTC).isoformat(timespec="seconds"),
                           "platform": _word(f"{platform.system()} {platform.machine()}")}
    if ml is not None:
        versions = ml.get("versions") or {}
        out["ml"] = {
            "ok": bool(ml.get("ok")),
            "versionsOk": all(bool(v.get("ok")) for v in versions.values()) if versions else False,
            "sileroVadOk": ml.get("sileroVad") == "ok",
            "models": {str(repo): bool(m.get("ok")) for repo, m in (ml.get("models") or {}).items()},
        }
        flags = ml.get("envFlags") or {}
        out["offline"] = {k: _word(flags.get(k), 8) for k in OFFLINE_FLAGS}
    else:
        out["ml"] = "unavailable"
    if net is not None:
        out["network"] = {"internetBlocked": bool(net.get("internetBlocked")), "redis": _word(net.get("redis")),
                          "spring": _word(str(net.get("spring", ""))), "isolatedAndConnected": bool(net.get("isolatedAndConnected"))}
    else:
        out["network"] = "unavailable"
    return out


def write(status: dict[str, Any], root: Path) -> Path:
    """Atomic write (temp file + rename), so Spring never reads a half-written file."""
    target = root / STATUS_RELATIVE
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f".{target.name}.{os.getpid()}.tmp")
    tmp.write_text(json.dumps(status, indent=1), encoding="utf-8")
    os.replace(tmp, target)
    return target


def collect() -> dict[str, Any]:
    ml = net = None
    try:
        from khmerdub_ai.ml import selfcheck as ml_selfcheck

        ml = ml_selfcheck.check(full_hash=False)
    except Exception:  # noqa: BLE001 - e.g. the Windows dev worker has no speech stack
        ml = None
    try:
        from khmerdub_ai.net import selfcheck as net_selfcheck

        net = net_selfcheck.check()
    except Exception:  # noqa: BLE001
        net = None
    return sanitize(ml, net)


def report() -> None:
    try:
        write(collect(), Path(get_settings().storage_root))
    except Exception:  # noqa: BLE001 - a status report must never take the worker down
        pass


@worker_ready.connect
def _on_worker_ready(**_: Any) -> None:
    threading.Thread(target=report, name="khmerdub-status", daemon=True).start()
