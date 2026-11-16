"""The single Celery task for engine work. The message carries only the attempt id (= fencing token)
and the stage; everything else comes from Spring at claim time."""

from __future__ import annotations

import importlib
import socket
from typing import Any

from khmerdub_ai.celery_app import app
from khmerdub_ai.config import get_settings
from khmerdub_ai.engine import faults
from khmerdub_ai.engine.runner import Handler, Tools, execute_attempt
from khmerdub_ai.engine.spring_client import SpringClient
from khmerdub_ai.stages.media import HANDLERS as MEDIA_HANDLERS

# Speech stages need numpy/torch/faster-whisper/pyannote, which exist only in the Linux ML worker image.
# They are imported on first use, so the Windows FastAPI process and the test venv never load them.
SPEECH_HANDLERS = {
    "SPEECH_MAP": ("khmerdub_ai.stages.speech", "speech_map"),
    "DETECT_LANGUAGE": ("khmerdub_ai.stages.speech", "detect_language"),
    "TRANSCRIBE": ("khmerdub_ai.stages.speech", "transcribe"),
    "DIARIZE": ("khmerdub_ai.stages.diarize", "diarize"),
    "RECONCILE_SPEAKERS": ("khmerdub_ai.stages.diarize", "reconcile_speakers"),
    "BUILD_DIALOGUE": ("khmerdub_ai.stages.diarize", "build_dialogue"),
}


class Handlers(dict):
    """Media handlers eagerly, speech handlers lazily."""

    def get(self, stage: str, default: Handler | None = None) -> Handler | None:  # type: ignore[override]
        if stage in MEDIA_HANDLERS:
            return MEDIA_HANDLERS[stage]
        if stage in SPEECH_HANDLERS:
            module, name = SPEECH_HANDLERS[stage]
            return getattr(importlib.import_module(module), name)
        return default


HANDLERS = Handlers()


@app.task(name="khmerdub.attempt.run", bind=True, ignore_result=True)
def run_attempt(self: Any, attemptId: str, stage: str) -> str:  # noqa: N803 - message field names
    settings = get_settings()
    client = SpringClient(settings.api_base_url, settings.internal_token.get_secret_value(),
                          host_header=settings.api_host_header)
    tools = Tools(ffmpeg=settings.ffmpeg_executable("ffmpeg"), ffprobe=settings.ffmpeg_executable("ffprobe"))
    rules = faults.load_rules(settings.storage_root) if settings.diagnostics_enabled else []
    try:
        return execute_attempt(attemptId, client=client, storage_root=settings.storage_root, tools=tools,
                               worker=self.request.hostname or socket.gethostname(), handlers=HANDLERS,
                               fault=(lambda claim: faults.match(rules, claim)) if rules else None)
    finally:
        client.close()
