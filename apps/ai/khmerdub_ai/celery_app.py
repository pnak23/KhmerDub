"""Celery application. Redis is the work queue only; PostgreSQL (via Spring Boot) is the source of truth."""

from __future__ import annotations

from celery import Celery

from khmerdub_ai.config import get_settings

settings = get_settings()

app = Celery(
    "khmerdub_ai",
    broker=settings.redis_url,
    backend=settings.redis_url,
    include=["khmerdub_ai.tasks.system", "khmerdub_ai.tasks.attempts"],
)

app.conf.update(
    task_serializer="json",
    result_serializer="json",
    accept_content=["json"],
    enable_utc=True,
    timezone="UTC",
    # Reliability: acknowledge only after the task finishes, so a crashed task is delivered again.
    task_acks_late=True,
    task_reject_on_worker_lost=True,
    worker_prefetch_multiplier=1,
    # Keep running work when the Redis connection drops. (True would kill every running task on a Redis
    # restart - measured in scenario S6.) Duplicates are harmless here: Spring fences every attempt, so a
    # message redelivered later is rejected at claim time with 409.
    worker_cancel_long_running_tasks_on_connection_loss=False,
    task_track_started=True,
    broker_connection_retry_on_startup=True,
    broker_transport_options={"visibility_timeout": settings.celery_visibility_timeout_s},
    result_expires=24 * 3600,
    # Queues: "cpu" = FFmpeg / whisper / diarization (one at a time); "io" = cloud calls (Gemini, TTS).
    task_default_queue="cpu",
    task_routes={"khmerdub.system.*": {"queue": "cpu"}},
)

# Register worker signal handlers (heartbeat; start-up self-report for the System page).
from khmerdub_ai import heartbeat  # noqa: E402,F401
from khmerdub_ai import status  # noqa: E402,F401
