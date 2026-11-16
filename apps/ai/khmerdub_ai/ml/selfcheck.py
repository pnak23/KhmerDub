"""Speech-stack self-check: package versions, Silero VAD hash, verified models, offline/telemetry switches.

Run: python -m khmerdub_ai.ml.selfcheck [--full-hash]   (exit 0 = everything verified)
"""

from __future__ import annotations

import importlib.metadata as md
import json
import os
import sys
from pathlib import Path

from khmerdub_ai.ml import models

PINNED = {"torch": "2.15.0+cpu", "torchaudio": "2.11.0+cpu", "faster-whisper": "1.2.1", "ctranslate2": "4.8.4",
          "onnxruntime": "1.31.1", "pyannote.audio": "4.0.7", "torchcodec": "0.17.0", "huggingface-hub": "2.3.0",
          "celery": "5.7.0", "python-dateutil": "2.9.0.post0"}


def check(full_hash: bool) -> dict:
    versions = {}
    for name, want in PINNED.items():
        try:
            have = md.version(name)
        except md.PackageNotFoundError:
            have = None
        versions[name] = {"installed": have, "ok": have == want}
    silero_ok, silero = models.verify_silero()
    root = Path(os.environ.get("KHMERDUB_MODELS_DIR", "/data/models"))
    model_results = {}
    if root.is_dir():
        for repo in (models.TURBO, models.SMALL, models.DIARIZATION):
            v = models.verify_model(root, repo, full_hash=full_hash)
            model_results[repo] = {"ok": v.ok, "files": v.files, "bytes": v.bytes, "problems": v.problems}
    flags = {k: os.environ.get(k) for k in ("HF_HUB_OFFLINE", "HF_HUB_DISABLE_TELEMETRY", "PYANNOTE_METRICS_ENABLED")}
    ok = all(v["ok"] for v in versions.values()) and silero_ok and all(m["ok"] for m in model_results.values())
    return {"versions": versions, "sileroVad": silero, "modelsRoot": str(root), "modelsChecked": bool(model_results),
            "fullHash": full_hash, "models": model_results, "envFlags": flags, "ok": ok}


if __name__ == "__main__":
    result = check("--full-hash" in sys.argv)
    print(json.dumps(result, indent=1))
    sys.exit(0 if result["ok"] else 1)
