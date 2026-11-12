"""Worker heartbeat: each worker writes a Redis key with a TTL.

The health check reads these keys. It does not use `celery inspect`, because a worker that is busy
with a long task (or runs the `solo` pool) cannot answer broadcast pings. A background thread keeps
the heartbeat alive in that case too.
"""

from __future__ import annotations

import json
import os
import platform
import sys
import tempfile
import threading
from datetime import UTC, datetime
from typing import Any

import httpx
import redis
from celery.signals import worker_ready, worker_shutdown

from khmerdub_ai.config import get_settings
from khmerdub_ai.health import WORKER_KEY_PREFIX, ffmpeg_version

_stop = threading.Event()
_state: dict[str, Any] = {}


def _describe(consumer: Any) -> dict[str, Any]:
    settings = get_settings()
    controller = getattr(consumer, "controller", None)
    pool_cls = getattr(controller, "pool_cls", None)
    pool = getattr(pool_cls, "__module__", str(pool_cls)).rsplit(".", 1)[-1] if pool_cls else None
    try:
        queues = sorted(q.name for q in consumer.task_consumer.queues)
    except Exception:  # noqa: BLE001
        queues = []
    ffmpeg = settings.ffmpeg_executable("ffmpeg")
    try:
        (settings.storage_root / "temp").mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=settings.storage_root / "temp", prefix=".hb-", delete=True):
            pass
        writable = True
    except OSError:
        writable = False
    return {
        "hostname": consumer.hostname,
        "os": sys.platform,
        "platform": platform.platform(),
        "python": platform.python_version(),
        "pid": os.getpid(),
        "pool": pool,
        "concurrency": getattr(controller, "concurrency", None),
        "queues": queues,
        "storageRoot": str(settings.storage_root),
        "storageWritable": writable,
        "ffmpeg": ffmpeg_version(ffmpeg) if ffmpeg else None,
        "startedAt": datetime.now(UTC).isoformat(),
    }


def _api_reachable() -> bool:
    """Can this worker call back into Spring Boot (/internal/ping, token-protected)? M2 result callbacks need it."""
    settings = get_settings()
    try:
        r = httpx.get(f"{settings.api_base_url}/internal/ping", timeout=2,
                      headers={"X-Internal-Token": settings.internal_token.get_secret_value()})
        return r.status_code == 200
    except httpx.HTTPError:
        return False


def _beat(client: redis.Redis, key: str, interval: int) -> None:
    while not _stop.is_set():
        payload = dict(_state, lastSeen=datetime.now(UTC).isoformat(), apiReachable=_api_reachable())
        try:
            client.set(key, json.dumps(payload), ex=interval * 3)
        except redis.RedisError:
            pass  # Redis is temporarily unavailable; keep trying.
        _stop.wait(interval)


@worker_ready.connect
def _on_worker_ready(sender: Any = None, **_: Any) -> None:
    settings = get_settings()
    _state.update(_describe(sender))
    key = f"{WORKER_KEY_PREFIX}{_state['hostname']}"
    _state["key"] = key
    client = redis.Redis.from_url(settings.redis_url, socket_timeout=5, socket_connect_timeout=5)
    threading.Thread(target=_beat, args=(client, key, settings.worker_heartbeat_interval_s),
                     name="khmerdub-heartbeat", daemon=True).start()


@worker_shutdown.connect
def _on_worker_shutdown(**_: Any) -> None:
    _stop.set()
    key = _state.get("key")
    if key:
        try:
            redis.Redis.from_url(get_settings().redis_url, socket_timeout=2).delete(key)
        except redis.RedisError:
            pass
