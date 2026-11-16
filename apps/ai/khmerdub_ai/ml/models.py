"""Verified local models and the offline runtime.

Models are downloaded once (M3a, pinned revisions) into <models_dir>/<org>--<name>/ and listed in
manifest.verified.json. At runtime they are only ever loaded from those directories with Hugging Face in offline
mode and pyannote telemetry off. Nothing here downloads anything.
"""

from __future__ import annotations

import gc
import hashlib
import json
import logging
import os
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

TURBO = "mobiuslabsgmbh/faster-whisper-large-v3-turbo"
SMALL = "Systran/faster-whisper-small"
DIARIZATION = "pyannote/speaker-diarization-community-1"

# Silero VAD ships inside the pinned faster-whisper wheel (verified against the wheel RECORD in M3b planning).
SILERO_RELATIVE = "assets/silero_vad_v6.onnx"
SILERO_SIZE = 1_245_151
SILERO_SHA256 = "4cbf549b8326f60f80f2536d9eefeb450a9abe83365a098031c89719f1be17d2"

_SKIP_SUFFIXES = (".gitattributes", ".gif")
_LOCK_FILE = Path(__file__).with_name("models.lock.json")


class ModelUnavailable(Exception):
    """A required model is missing, not verified, or does not match the pinned lock."""


def configure_offline() -> None:
    """Runtime switches: no Hugging Face network access, no telemetry. Safe to call repeatedly."""
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["HF_HUB_DISABLE_TELEMETRY"] = "1"
    os.environ["PYANNOTE_METRICS_ENABLED"] = "0"


def disable_pyannote_telemetry() -> None:
    configure_offline()
    from pyannote.audio.telemetry import set_telemetry_metrics

    set_telemetry_metrics(False)


def lock() -> dict[str, Any]:
    return json.loads(_LOCK_FILE.read_text(encoding="utf-8"))


def model_dir(models_root: Path, repo: str) -> Path:
    return models_root / repo.replace("/", "--")


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def _git_oid(path: Path) -> str:
    data = path.read_bytes()
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


@dataclass
class Verification:
    repo: str
    ok: bool
    problems: list[str]
    files: int
    bytes: int
    revision: str | None


def verify_model(models_root: Path, repo: str, *, full_hash: bool = False) -> Verification:
    """Check a model against the pinned lock and the verified manifest. full_hash re-hashes every file."""
    info = lock().get(repo)
    if info is None:
        return Verification(repo, False, ["not in models.lock.json"], 0, 0, None)
    problems: list[str] = []
    manifest_path = models_root / "manifest.verified.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")).get("models", {})
    except (OSError, ValueError):
        manifest = {}
    entry = manifest.get(repo)
    if entry is None:
        problems.append("not listed in manifest.verified.json")
    elif entry.get("revision") != info["revision"]:
        problems.append("manifest revision differs from the lock")
    target = model_dir(models_root, repo)
    files = total = 0
    for f in info["files"]:
        if f["path"].endswith(_SKIP_SUFFIXES):
            continue
        p = target.joinpath(*f["path"].split("/"))
        if not p.is_file():
            problems.append(f"missing {f['path']}")
            continue
        size = p.stat().st_size
        if f.get("size") is not None and size != f["size"]:
            problems.append(f"size mismatch {f['path']}")
            continue
        if full_hash:
            if f.get("sha256") and _sha256(p) != f["sha256"]:
                problems.append(f"sha256 mismatch {f['path']}")
            elif f.get("git_oid") and _git_oid(p) != f["git_oid"]:
                problems.append(f"git oid mismatch {f['path']}")
        files += 1
        total += size
    return Verification(repo, not problems, problems, files, total, info["revision"])


def verify_silero() -> tuple[bool, str]:
    import faster_whisper

    p = Path(faster_whisper.__file__).parent / SILERO_RELATIVE
    if not p.is_file():
        return False, "missing"
    if p.stat().st_size != SILERO_SIZE:
        return False, "size mismatch"
    if _sha256(p) != SILERO_SHA256:
        return False, "sha256 mismatch"
    return True, "ok"


class ModelCache:
    """Keeps at most ONE model family in memory (ASR or diarization) to bound RAM (~2.5 GB peak).

    Models are loaded only from verified local directories. Thread-safe; meant for the single worker process.
    """

    def __init__(self, models_root: Path):
        self._root = models_root
        self._lock = threading.Lock()
        self._key: tuple[Any, ...] | None = None
        self._model: Any = None
        self._verified: set[str] = set()

    def _require(self, repo: str) -> Path:
        if repo not in self._verified:
            v = verify_model(self._root, repo, full_hash=True)
            if not v.ok:
                raise ModelUnavailable(f"{repo}: {'; '.join(v.problems)}")
            self._verified.add(repo)
        return model_dir(self._root, repo)

    def _swap(self, key: tuple[Any, ...], loader) -> Any:
        with self._lock:
            if self._key == key:
                return self._model
            self._model = None
            self._key = None
            gc.collect()
            log.info("loading model %s", key[0])
            self._model = loader()
            self._key = key
            return self._model

    def whisper(self, repo: str, threads: int):
        path = self._require(repo)
        configure_offline()

        def load():
            from faster_whisper import WhisperModel

            return WhisperModel(str(path), device="cpu", compute_type="int8", cpu_threads=threads, num_workers=1,
                                local_files_only=True)

        return self._swap(("whisper", repo, threads), load)

    def diarization(self, threads: int):
        path = self._require(DIARIZATION)
        disable_pyannote_telemetry()

        def load():
            import torch
            from pyannote.audio import Pipeline

            torch.set_num_threads(threads)
            return Pipeline.from_pretrained(str(path))

        return self._swap(("pyannote", DIARIZATION, threads), load)

    def release(self) -> None:
        with self._lock:
            self._model = None
            self._key = None
            gc.collect()
