"""Speaker stages: DIARIZE (pyannote community-1, per chunk), RECONCILE_SPEAKERS and BUILD_DIALOGUE.

PRIVACY: speaker embeddings are written only to the attempt's private/fingerprints folder as .npy files, voice
samples only to private/voice-samples. Their values are never logged, printed or put into JSON outputs.
"""

from __future__ import annotations

import logging
import time
from typing import Any

import numpy as np

from khmerdub_ai.engine.runner import AttemptContext, Cancelled, InvalidInput
from khmerdub_ai.speechlogic import dialogue as dialogue_logic
from khmerdub_ai.speechlogic.speakers import Chunk, choose_samples, global_centroids, reconcile
from khmerdub_ai.stages.common import (SAMPLE_RATE, memory_metrics, output, private_path, read_json, read_wav,
                                       write_json, write_wav)
from khmerdub_ai.stages.speech import cache

log = logging.getLogger(__name__)


class _Progress:
    """pyannote hook: maps pipeline steps to attempt progress and stops promptly on cancel."""

    WEIGHTS = {"segmentation": (0.0, 8.0), "speaker_counting": (8.0, 10.0), "embeddings": (10.0, 97.0),
               "discrete_diarization": (97.0, 99.0)}

    def __init__(self, ctx: AttemptContext):
        self.ctx = ctx

    def __call__(self, step_name, step_artifact, file=None, total=None, completed=None):  # noqa: ARG002
        if self.ctx.cancelled:
            raise Cancelled("fenced" if self.ctx.fenced else "cancelled")
        lo, hi = self.WEIGHTS.get(step_name, (None, None))
        if lo is not None:
            frac = (completed / total) if total and completed is not None else 1.0
            self.ctx.set_progress(lo + (hi - lo) * max(0.0, min(1.0, frac)))


def _turns(annotation, offset_ms: int) -> list[dict[str, Any]]:
    return [{"s": offset_ms + int(round(seg.start * 1000)), "e": offset_ms + int(round(seg.end * 1000)), "spk": spk}
            for seg, _, spk in annotation.itertracks(yield_label=True)]


def diarize(ctx: AttemptContext) -> dict[str, Any]:
    import torch

    p = ctx.input
    start_ms, end_ms, index = int(p["startMs"]), int(p["endMs"]), int(p["chunkIndex"])
    audio = read_wav(ctx.resolve(p["audioPath"]), start_ms, end_ms)
    if audio.size == 0:
        raise InvalidInput("empty audio range")
    pipeline = cache().diarization(int(p.get("threads", 8)))
    step = float(p.get("segmentationStep", 0.1))
    pipeline.segmentation_step = step
    pipeline._segmentation.step = step * pipeline._segmentation.duration  # noqa: SLF001 - verified in M3a
    t0 = time.monotonic()
    out = pipeline({"waveform": torch.from_numpy(audio).unsqueeze(0), "sample_rate": SAMPLE_RATE},
                   hook=_Progress(ctx))
    ctx.check_cancelled()
    annotation = getattr(out, "speaker_diarization", out)
    exclusive = getattr(out, "exclusive_speaker_diarization", None) or annotation
    labels = list(annotation.labels())
    embeddings = getattr(out, "speaker_embeddings", None)
    rows, kept = [], []
    if embeddings is not None:
        for i, spk in enumerate(labels):
            if i < len(embeddings) and np.all(np.isfinite(embeddings[i])):
                rows.append(np.asarray(embeddings[i], dtype=np.float32))
                kept.append(spk)
    emb_path = private_path(ctx, "fingerprints", f"diarize-{index:04d}.npy")
    emb_path.parent.mkdir(parents=True, exist_ok=True)
    np.save(emb_path, np.stack(rows) if rows else np.zeros((0, 0), dtype=np.float32))
    turns_path = ctx.work_dir / f"diarization-{index:04d}.json"
    write_json(turns_path, {"format": "khmerdub.diarization-chunk.v1", "chunkIndex": index, "startMs": start_ms,
                            "endMs": end_ms, "segmentationStep": step, "labels": labels,
                            "embeddingLabels": kept,  # row order of the .npy file
                            "turns": _turns(annotation, start_ms), "exclusiveTurns": _turns(exclusive, start_ms)})
    return {"outputs": [output(ctx, "diarization", turns_path, {"speakers": len(labels)}),
                        output(ctx, "embeddings", emb_path, {"rows": len(rows)})],
            "metrics": {"diarizeSeconds": round(time.monotonic() - t0, 1),
                        "rtf": round((time.monotonic() - t0) / max(1e-6, (end_ms - start_ms) / 1000), 3),
                        **memory_metrics()}}


def reconcile_speakers(ctx: AttemptContext) -> dict[str, Any]:
    p = ctx.input
    chunks: list[Chunk] = []
    for c in p["chunks"]:
        meta = read_json(ctx, c["diarizationPath"])
        matrix = np.load(ctx.resolve(c["embeddingsPath"]), allow_pickle=False)
        centroids = {spk: matrix[i].astype(float).tolist() for i, spk in enumerate(meta["embeddingLabels"])
                     if i < len(matrix)}
        chunks.append(Chunk(int(meta["chunkIndex"]), int(meta["startMs"]), int(meta["endMs"]),
                            meta["exclusiveTurns"], centroids))
        ctx.check_cancelled()
    rec = reconcile(chunks, float(p.get("threshold", 0.5)))
    ctx.set_progress(50.0)
    outputs = []
    centroids = global_centroids(chunks, rec)
    codes = [s["code"] for s in rec.speakers if s["code"] in centroids]
    emb_path = private_path(ctx, "fingerprints", "speakers.npy")
    emb_path.parent.mkdir(parents=True, exist_ok=True)
    np.save(emb_path, np.asarray([centroids[c] for c in codes], dtype=np.float32) if codes
            else np.zeros((0, 0), dtype=np.float32))
    audio_path = ctx.resolve(p["audioPath"])
    samples = []
    for sp in rec.speakers:
        for n, (s, e) in enumerate(choose_samples(rec.global_turns, sp["code"], int(p.get("samplesPerSpeaker", 3))), 1):
            path = private_path(ctx, "voice-samples", f"{sp['code']}-{n}.wav")
            write_wav(path, read_wav(audio_path, s, e))
            samples.append(output(ctx, "sample", path, {"speaker": sp["code"], "startMs": s, "endMs": e}))
        ctx.check_cancelled()
    speakers_path = ctx.work_dir / "speakers.json"
    write_json(speakers_path, {"format": "khmerdub.speakers.v1", "threshold": float(p.get("threshold", 0.5)),
                               "mustLinks": rec.must_links, "refusedMustLinks": rec.refused_must_links,
                               "embeddingMerges": rec.embedding_merges, "speakers": rec.speakers,
                               "embeddingCodes": codes,  # row order of speakers.npy
                               "mapping": [{"chunk": c, "local": spk, "global": g}
                                           for (c, spk), g in sorted(rec.mapping.items())],
                               "turns": rec.global_turns})
    outputs.append(output(ctx, "speakers", speakers_path, {"speakers": len(rec.speakers)}))
    outputs.append(output(ctx, "embeddings", emb_path, {"rows": len(codes)}))
    outputs.extend(samples)
    return {"outputs": outputs, "metrics": {"speakers": len(rec.speakers), "mustLinks": rec.must_links,
                                            "refusedMustLinks": rec.refused_must_links, **memory_metrics()}}


def build_dialogue(ctx: AttemptContext) -> dict[str, Any]:
    p = ctx.input
    transcripts = [read_json(ctx, path) for path in p["transcriptPaths"]]
    turns = read_json(ctx, p["speakersPath"])["turns"] if p.get("speakersPath") else None
    regions = read_json(ctx, p["speechMapPath"])["regions"]
    result = dialogue_logic.build(transcripts, turns, regions)
    result["language"] = p.get("language")
    out = ctx.work_dir / "dialogue.json"
    write_json(out, result)
    return {"outputs": [output(ctx, "dialogue", out, result["summary"])], "metadata": {"summary": result["summary"]},
            "metrics": memory_metrics()}
