"""Helpers shared by the speech stages: reading 16 kHz mono WAV slices and describing output files."""

from __future__ import annotations

import json
import wave
from pathlib import Path
from typing import Any

import numpy as np

from khmerdub_ai.engine.runner import AttemptContext, InvalidInput
from khmerdub_ai.stages.media import sha256_of

SAMPLE_RATE = 16_000
PRIVATE_DIR = "private"  # inside the attempt folder; Spring publishes from here and wipes it when the attempt ends


def read_wav(path: Path, start_ms: int = 0, end_ms: int | None = None) -> np.ndarray:
    """Float32 samples of [start_ms, end_ms) from a 16 kHz mono 16-bit WAV."""
    with wave.open(str(path), "rb") as w:
        if w.getframerate() != SAMPLE_RATE or w.getnchannels() != 1 or w.getsampwidth() != 2:
            raise InvalidInput(f"{path.name}: expected 16 kHz mono 16-bit WAV")
        first = start_ms * SAMPLE_RATE // 1000
        last = w.getnframes() if end_ms is None else min(w.getnframes(), end_ms * SAMPLE_RATE // 1000)
        if first >= last:
            return np.zeros(0, dtype=np.float32)
        w.setpos(first)
        data = np.frombuffer(w.readframes(last - first), dtype=np.int16)
    return data.astype(np.float32) / 32768.0


def write_wav(path: Path, samples: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes((np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16).tobytes())


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))


def read_json(ctx: AttemptContext, relative: str) -> dict[str, Any]:
    with open(ctx.resolve(relative), encoding="utf-8") as f:
        return json.load(f)


def output(ctx: AttemptContext, role: str, path: Path, info: dict[str, Any] | None = None) -> dict[str, Any]:
    """Describe a produced file for Spring (which re-checks size and SHA-256 before publishing)."""
    return {"role": role, "path": ctx.relative(path), "sizeBytes": path.stat().st_size, "sha256": sha256_of(path),
            "info": info or {}}


def memory_metrics() -> dict[str, int]:
    """Peak memory of this worker process (and of the container cgroup, when available), in MB."""
    import resource

    metrics = {"processMaxRssMB": round(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024)}
    try:
        with open("/sys/fs/cgroup/memory.peak") as f:
            metrics["cgroupMemoryPeakMB"] = round(int(f.read().strip()) / 2**20)
    except (OSError, ValueError):
        pass
    return metrics


def private_path(ctx: AttemptContext, kind: str, name: str) -> Path:
    """Location for sensitive outputs (speaker embeddings, voice samples) inside the attempt folder."""
    return ctx.work_dir / PRIVATE_DIR / kind / name
