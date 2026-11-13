"""The single Celery task for engine work. The message carries only the attempt id (= fencing token)
and the stage; everything else comes from Spring at claim time."""

from __future__ import annotations

import socket
from typing import Any

from khmerdub_ai.celery_app import app
from khmerdub_ai.config import get_settings
from khmerdub_ai.engine import faults
from khmerdub_ai.engine.runner import Tools, execute_attempt
from khmerdub_ai.engine.spring_client import SpringClient
from khmerdub_ai.stages.media import HANDLERS


@app.task(name="khmerdub.attempt.run", bind=True, ignore_result=True)
def run_attempt(self: Any, attemptId: str, stage: str) -> str:  # noqa: N803 - message field names
    settings = get_settings()
    client = SpringClient(settings.api_base_url, settings.internal_token.get_secret_value())
    tools = Tools(ffmpeg=settings.ffmpeg_executable("ffmpeg"), ffprobe=settings.ffmpeg_executable("ffprobe"))
    rules = faults.load_rules(settings.storage_root) if settings.diagnostics_enabled else []
    try:
        return execute_attempt(attemptId, client=client, storage_root=settings.storage_root, tools=tools,
                               worker=self.request.hostname or socket.gethostname(), handlers=HANDLERS,
                               fault=(lambda claim: faults.match(rules, claim)) if rules else None)
    finally:
        client.close()
