"""KhmerDub AI service (FastAPI). Internal only: called by Spring Boot, never by the browser."""

from __future__ import annotations

import hmac
import json
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated, Any, Literal
from uuid import UUID

import redis
from celery.exceptions import TimeoutError as CeleryTimeoutError
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

from khmerdub_ai import __version__, health
from khmerdub_ai.celery_app import app as celery_app
from khmerdub_ai.config import Settings, get_settings
from khmerdub_ai.power import MAX_TTL_S, MIN_TTL_S, KeepAwake
from khmerdub_ai.storage import resolve_in_storage

# Keep-awake lives in THIS (Windows) process only. Spring holds it while a heavy job is actively processing.
keep_awake = KeepAwake()


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    yield
    keep_awake.release(why="released at application exit")
    keep_awake.stop()


app = FastAPI(title="KhmerDub AI service", version=__version__, docs_url=None, redoc_url=None, openapi_url=None,
              lifespan=lifespan)
# DNS-rebinding guard: only loopback Host headers are accepted.
app.add_middleware(TrustedHostMiddleware, allowed_hosts=["127.0.0.1", "localhost"])

_redis_client: redis.Redis | None = None


def get_redis(settings: Annotated[Settings, Depends(get_settings)]) -> redis.Redis:
    global _redis_client
    if _redis_client is None:
        _redis_client = redis.Redis.from_url(settings.redis_url, socket_timeout=2, socket_connect_timeout=2)
    return _redis_client


def require_internal_token(
    settings: Annotated[Settings, Depends(get_settings)],
    x_internal_token: Annotated[str | None, Header()] = None,
) -> None:
    expected = settings.internal_token.get_secret_value().encode("utf-8")
    if not x_internal_token or not hmac.compare_digest(x_internal_token.encode("utf-8"), expected):
        raise HTTPException(status_code=401, detail="Invalid internal token")


@app.get("/livez")
def livez() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health", dependencies=[Depends(require_internal_token)])
def get_health(
    settings: Annotated[Settings, Depends(get_settings)],
    client: Annotated[Any, Depends(get_redis)],
) -> dict[str, Any]:
    components = {
        "redis": health.check_redis(client),
        "ffmpeg": health.check_ffmpeg(settings),
        "storage": health.check_storage(settings.storage_root, settings.min_free_disk_gb),
        "workers": health.check_workers(client),
    }
    return {
        "status": health.overall(components),
        "service": "khmerdub-ai",
        "version": __version__,
        "cloudEnabled": settings.cloud_enabled,
        "components": components,
    }


class DispatchRequest(BaseModel):
    attemptId: UUID  # noqa: N815 - matches the Spring payload
    stage: str = Field(min_length=1, max_length=40)
    queue: Literal["cpu", "io"] = "cpu"


@app.post("/internal/tasks/dispatch", status_code=202, dependencies=[Depends(require_internal_token)])
def dispatch(request: DispatchRequest) -> dict[str, Any]:
    """Queue one attempt. The Celery task id is the attempt id (the fencing token). Spring has already
    committed the attempt; if this call fails, Spring releases the chunk and dispatches again later."""
    try:
        celery_app.send_task("khmerdub.attempt.run",
                             kwargs={"attemptId": str(request.attemptId), "stage": request.stage},
                             task_id=str(request.attemptId), queue=request.queue,
                             retry=True, retry_policy={"max_retries": 2, "interval_start": 0,
                                                       "interval_step": 0.5, "interval_max": 1})
    except Exception as e:  # noqa: BLE001 - broker unreachable
        raise HTTPException(status_code=503, detail=f"Queue unavailable: {type(e).__name__}") from e
    return {"queued": True, "attemptId": str(request.attemptId)}


class PowerHoldRequest(BaseModel):
    holder: str = Field(min_length=1, max_length=60)
    ttlSeconds: int = Field(ge=MIN_TTL_S, le=MAX_TTL_S)  # noqa: N815 - matches the Spring payload
    reason: str | None = Field(default=None, max_length=200)


class PowerReleaseRequest(BaseModel):
    holder: str = Field(min_length=1, max_length=60)
    reason: str | None = Field(default=None, max_length=200)


def _power_status() -> dict[str, Any]:
    s = keep_awake.status()
    return {"supported": s.supported, "held": s.held, "holder": s.holder, "reason": s.reason,
            "heldSince": s.held_since, "expiresInSeconds": None if s.expires_in_s is None else round(s.expires_in_s, 1),
            "acquisitions": s.acquisitions, "releases": s.releases}


@app.post("/internal/power/hold", dependencies=[Depends(require_internal_token)])
def power_hold(request: PowerHoldRequest) -> dict[str, Any]:
    """Acquire or renew the keep-awake lease. Without renewal it is released when the TTL expires."""
    keep_awake.hold(request.holder, request.ttlSeconds, request.reason)
    keep_awake.wait_idle(0.5)
    return _power_status()


@app.post("/internal/power/release", dependencies=[Depends(require_internal_token)])
def power_release(request: PowerReleaseRequest) -> dict[str, Any]:
    keep_awake.release(request.holder, why=f"released by {request.holder}: {request.reason or 'no reason given'}")
    keep_awake.wait_idle(0.5)
    return _power_status()


@app.get("/internal/power/status", dependencies=[Depends(require_internal_token)])
def power_status() -> dict[str, Any]:
    return _power_status()


@app.post("/internal/diagnostics/roundtrip", dependencies=[Depends(require_internal_token)])
def roundtrip(settings: Annotated[Settings, Depends(get_settings)]) -> dict[str, Any]:
    """Send a ping task through Redis to a worker. Then confirm that this process can read the file the
    worker wrote, which proves the worker and the API share the same storage."""
    started = time.perf_counter()
    result = celery_app.send_task("khmerdub.system.ping", kwargs={"probe": True}, queue="cpu", expires=15)
    try:
        worker = result.get(timeout=5)
    except CeleryTimeoutError:
        return {"ok": False, "error": "No worker finished the task within 5 s (worker not running or busy)."}
    except Exception as e:  # noqa: BLE001
        return {"ok": False, "error": f"{type(e).__name__}: {e}"}
    elapsed_ms = round((time.perf_counter() - started) * 1000)

    shared: dict[str, Any] = {"path": worker.get("probeFile")}
    try:
        probe = resolve_in_storage(settings.storage_root, worker["probeFile"])
        content = json.loads(probe.read_text(encoding="utf-8"))
        shared["visibleToApi"] = content.get("taskId") == worker.get("taskId")
        probe.unlink()
    except (OSError, KeyError, ValueError) as e:
        shared["visibleToApi"] = False
        shared["error"] = f"{type(e).__name__}: {e}"
    return {"ok": bool(shared["visibleToApi"]), "elapsedMs": elapsed_ms, "worker": worker, "sharedStorage": shared}
