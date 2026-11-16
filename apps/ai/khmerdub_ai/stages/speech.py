"""Speech stages: SPEECH_MAP (Silero VAD), DETECT_LANGUAGE and TRANSCRIBE (faster-whisper large-v3-turbo int8).

All models run offline from verified local directories (see khmerdub_ai.ml.models). Timestamps in every output
are ABSOLUTE milliseconds/seconds in the movie, so Spring and BUILD_DIALOGUE never re-offset anything.
"""

from __future__ import annotations

import logging
import time
from typing import Any

from khmerdub_ai.engine.runner import AttemptContext, InvalidInput
from khmerdub_ai.ml import models
from khmerdub_ai.speechlogic.timing import merge_regions, pick_windows, repair_first_words
from khmerdub_ai.stages.common import SAMPLE_RATE, memory_metrics, output, read_json, read_wav, write_json

log = logging.getLogger(__name__)

_CACHE: models.ModelCache | None = None

# Speech map: fine-grained regions (short silences kept) for chunk snapping and coverage checks.
SPEECH_MAP_VAD = {"threshold": 0.5, "min_speech_duration_ms": 120, "min_silence_duration_ms": 300, "speech_pad_ms": 60}
SPEECH_MAP_BLOCK_MS = 300_000


def cache() -> models.ModelCache:
    global _CACHE
    if _CACHE is None:
        from khmerdub_ai.config import get_settings

        _CACHE = models.ModelCache(get_settings().models_dir)
    return _CACHE


# ----------------------------------------------------------------------------------------------- SPEECH_MAP

def speech_map(ctx: AttemptContext) -> dict[str, Any]:
    from faster_whisper.vad import VadOptions, get_speech_timestamps

    ok, why = models.verify_silero()
    if not ok:
        raise InvalidInput(f"Silero VAD model check failed: {why}")
    audio_path = ctx.resolve(ctx.input["audioPath"])
    duration_ms = int(ctx.input["durationMs"])
    options = VadOptions(**SPEECH_MAP_VAD)
    regions: list[list[int]] = []
    started = time.monotonic()
    for block_start in range(0, duration_ms, SPEECH_MAP_BLOCK_MS):
        ctx.check_cancelled()
        block_end = min(duration_ms, block_start + SPEECH_MAP_BLOCK_MS)
        audio = read_wav(audio_path, block_start, block_end)
        for ts in get_speech_timestamps(audio, options):
            regions.append([block_start + ts["start"] * 1000 // SAMPLE_RATE, block_start + ts["end"] * 1000 // SAMPLE_RATE])
        ctx.set_progress(100.0 * block_end / duration_ms)
    regions = merge_regions(regions, join_gap_ms=20)  # re-join regions split at block boundaries
    speech_ms = sum(e - s for s, e in regions)
    out = ctx.work_dir / "speech-map.json"
    write_json(out, {"format": "khmerdub.speech-map.v1", "durationMs": duration_ms, "vad": SPEECH_MAP_VAD,
                     "regions": regions, "speechMs": speech_ms})
    return {"outputs": [output(ctx, "speechMap", out, {"regions": len(regions), "speechMs": speech_ms})],
            "metrics": {"vadSeconds": round(time.monotonic() - started, 1)}}


# ------------------------------------------------------------------------------------------ DETECT_LANGUAGE

def detect_language(ctx: AttemptContext) -> dict[str, Any]:
    p = ctx.input
    audio_path = ctx.resolve(p["audioPath"])
    regions = read_json(ctx, p["speechMapPath"])["regions"]
    model = cache().whisper(models.TURBO, int(p.get("threads", 4)))
    windows = []
    starts = pick_windows(regions, int(p["durationMs"]), int(p.get("windows", 6)))
    for i, start in enumerate(starts):
        ctx.check_cancelled()
        lang, prob, _ = model.detect_language(read_wav(audio_path, start, start + 30_000))
        windows.append({"atMs": start, "language": lang, "probability": round(float(prob), 4)})
        ctx.set_progress(100.0 * (i + 1) / len(starts))
    votes: dict[str, float] = {}
    for w in windows:
        votes[w["language"]] = votes.get(w["language"], 0.0) + w["probability"]
    language = max(votes, key=votes.get)
    agreeing = [w["probability"] for w in windows if w["language"] == language]
    return {"outputs": [], "metadata": {"language": language, "windows": windows,
                                        "agreement": round(len(agreeing) / len(windows), 3),
                                        "meanProbability": round(sum(agreeing) / len(agreeing), 4)}}


# ----------------------------------------------------------------------------------------------- TRANSCRIBE

def transcribe(ctx: AttemptContext) -> dict[str, Any]:
    p = ctx.input
    start_ms, end_ms = int(p["startMs"]), int(p["endMs"])
    audio = read_wav(ctx.resolve(p["audioPath"]), start_ms, end_ms)
    if audio.size == 0:
        raise InvalidInput("empty audio range")
    offset = start_ms / 1000.0
    span = max(1e-6, (end_ms - start_ms) / 1000.0)
    model = cache().whisper(models.TURBO, int(p.get("threads", 4)))
    t0 = time.monotonic()
    segments_iter, info = model.transcribe(
        audio, language=p["language"], beam_size=int(p.get("beamSize", 5)), word_timestamps=True, vad_filter=True,
        condition_on_previous_text=False, hallucination_silence_threshold=2.0)
    segments: list[dict[str, Any]] = []
    for s in segments_iter:  # generator: decoding happens while iterating, so cancellation is checked often
        ctx.check_cancelled()
        if s.end * 1000 + start_ms > end_ms + 1000:  # a hallucinated timestamp past the chunk end
            log.warning("chunk %s: segment beyond chunk end ignored", p["chunkIndex"])
            continue
        words = [{"w": w.word, "s": round(w.start + offset, 3), "e": round(w.end + offset, 3), "p": round(w.probability, 3)}
                 for w in (s.words or [])]
        segments.append({"start": round(s.start + offset, 3), "end": round(s.end + offset, 3), "text": s.text.strip(),
                         "avgLogprob": round(s.avg_logprob, 4), "noSpeechProb": round(s.no_speech_prob, 4),
                         "compressionRatio": round(s.compression_ratio, 3), "words": words})
        ctx.set_progress(min(99.0, 100.0 * s.end / span))
    repaired = repair_first_words(segments)
    out = ctx.work_dir / f"transcript-{int(p['chunkIndex']):04d}.json"
    write_json(out, {"format": "khmerdub.transcript-chunk.v1", "chunkIndex": int(p["chunkIndex"]),
                     "startMs": start_ms, "endMs": end_ms, "coreStartMs": int(p["coreStartMs"]),
                     "coreEndMs": int(p["coreEndMs"]), "language": p["language"], "model": models.TURBO,
                     "beamSize": int(p.get("beamSize", 5)), "segments": segments,
                     "firstWordsRepaired": repaired,
                     "speechSecondsAfterVad": round(float(getattr(info, "duration_after_vad", 0) or 0), 2)})
    words = sum(len(s["words"]) for s in segments)
    return {"outputs": [output(ctx, "transcript", out, {"segments": len(segments), "words": words})],
            "metrics": {"transcribeSeconds": round(time.monotonic() - t0, 1),
                        "rtf": round((time.monotonic() - t0) / span, 3), "firstWordsRepaired": repaired,
                        **memory_metrics()}}
