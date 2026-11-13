"""Media stages: inspect the movie, extract 16 kHz mono audio segments, merge them.

Segment extraction is sample-exact: every segment contains exactly (end - start) * 16000 / 1000 samples
(padded with silence or trimmed). Concatenating the segments therefore gives one track whose sample n is at
time n / 16000 s of the ORIGINAL movie. Output is deterministic (bit-exact flags), so a re-run of the same
segment produces an identical file.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import time
import wave
from fractions import Fraction
from pathlib import Path
from typing import Any

from khmerdub_ai.engine.runner import AttemptContext, InvalidInput

CHANNELS = 1
SAMPLE_WIDTH = 2  # 16-bit PCM


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def wav_frames(path: Path) -> int:
    with wave.open(str(path), "rb") as w:
        return w.getnframes()


def _output(ctx: AttemptContext, path: Path, samples: int) -> dict[str, Any]:
    return {"role": "audio", "path": ctx.relative(path), "sizeBytes": path.stat().st_size,
            "sha256": sha256_of(path), "info": {"samples": samples}}


# --------------------------------------------------------------------------------------------- inspect

def inspect(ctx: AttemptContext) -> dict[str, Any]:
    original = ctx.resolve(ctx.input["originalPath"])
    if not original.is_file():
        raise InvalidInput(f"Movie file not found: {ctx.input['originalPath']}")
    if not ctx.tools.ffprobe:
        raise RuntimeError("ffprobe is not available on this worker")
    started = time.monotonic()
    proc = subprocess.run([ctx.tools.ffprobe, "-v", "error", "-print_format", "json", "-show_format",
                           "-show_streams", str(original)], capture_output=True, text=True, timeout=600)
    if proc.returncode != 0:
        raise InvalidInput(f"The file could not be read as a movie: {proc.stderr.strip()[-500:]}")
    summary = summarize(json.loads(proc.stdout))
    ctx.set_progress(100)
    return {"outputs": [], "metadata": summary,
            "metrics": {"probeMs": round((time.monotonic() - started) * 1000)}}


def summarize(probe: dict[str, Any]) -> dict[str, Any]:
    fmt = probe.get("format", {})
    streams = probe.get("streams", [])

    def duration_ms(value: Any) -> int | None:
        try:
            return round(float(value) * 1000)
        except (TypeError, ValueError):
            return None

    def fps(rate: str | None) -> float | None:
        try:
            return round(float(Fraction(rate)), 3) if rate and rate != "0/0" else None
        except (ValueError, ZeroDivisionError):
            return None

    video, audio, subtitles = [], [], 0
    for s in streams:
        kind = s.get("codec_type")
        tags = s.get("tags", {})
        disposition = s.get("disposition", {})
        if kind == "video" and not disposition.get("attached_pic"):
            video.append({"index": s["index"], "codec": s.get("codec_name"), "width": s.get("width"),
                          "height": s.get("height"), "fps": fps(s.get("avg_frame_rate")),
                          "pixFmt": s.get("pix_fmt")})
        elif kind == "audio":
            audio.append({"index": s["index"], "codec": s.get("codec_name"), "channels": s.get("channels"),
                          "channelLayout": s.get("channel_layout"),
                          "sampleRate": int(s["sample_rate"]) if s.get("sample_rate") else None,
                          "language": tags.get("language"), "title": tags.get("title"),
                          "default": bool(disposition.get("default")),
                          "durationMs": duration_ms(s.get("duration"))})
        elif kind == "subtitle":
            subtitles += 1
    return {"formatName": fmt.get("format_name"), "formatLongName": fmt.get("format_long_name"),
            "durationMs": duration_ms(fmt.get("duration")),
            "sizeBytes": int(fmt["size"]) if fmt.get("size") else None,
            "bitRate": int(fmt["bit_rate"]) if fmt.get("bit_rate") else None,
            "videoStreams": video, "audioStreams": audio, "subtitleStreams": subtitles}


# --------------------------------------------------------------------------------------------- extract

PREROLL_MS = 5000


def extract_segment_command(original: Path, out: Path, start_ms: int, end_ms: int, stream_index: int,
                            sample_rate: int) -> list[str]:
    """Sample-accurate segment extraction.

    Seeking straight to the segment start is NOT accurate for AAC: the decoder restarts mid-stream and the
    output can be off by up to one frame (measured 5-39 ms). Instead: seek PREROLL_MS earlier, keep the
    container timestamps (-copyts) and cut at the exact start with atrim on those timestamps. At the start of
    the movie no seek is used at all (-ss 0 drops the first AAC frame). Measured against a continuous
    decode: exact for MP4/MOV; within 3 samples (0.2 ms) for MKV, whose timestamps have 1 ms resolution.
    apad + atrim then make the length exact, so segments concatenate without gaps or overlaps.
    """
    samples = (end_ms - start_ms) * sample_rate // 1000
    seek_ms = max(0, start_ms - PREROLL_MS)
    args = ["-hide_banner", "-nostdin", "-v", "error", "-y"]
    if seek_ms > 0:
        args += ["-ss", f"{seek_ms / 1000:.3f}"]
    args += ["-t", f"{(end_ms - seek_ms) / 1000 + 1:.3f}", "-copyts", "-i", str(original),
             "-map", f"0:{stream_index}", "-vn", "-sn", "-dn",
             "-af", (f"atrim=start={start_ms / 1000:.6f},asetpts=PTS-STARTPTS,aresample={sample_rate},"
                     f"apad=whole_len={samples},atrim=end_sample={samples}"),
             "-ac", str(CHANNELS), "-c:a", "pcm_s16le",
             "-map_metadata", "-1", "-fflags", "+bitexact", "-flags:a", "+bitexact",
             "-f", "wav", str(out)]
    return args


def extract_segment(ctx: AttemptContext) -> dict[str, Any]:
    start_ms, end_ms = int(ctx.claim["startMs"]), int(ctx.claim["endMs"])
    sample_rate = int(ctx.input["sampleRate"])
    expected = int(ctx.input["expectedSamples"])
    original = ctx.resolve(ctx.input["originalPath"])
    if not original.is_file():
        raise InvalidInput(f"Movie file not found: {ctx.input['originalPath']}")
    out = ctx.work_dir / "segment.wav"
    started = time.monotonic()
    ctx.run_ffmpeg(extract_segment_command(original, out, start_ms, end_ms, int(ctx.input["audioStreamIndex"]),
                                           sample_rate), expected_duration_s=(end_ms - start_ms) / 1000)
    ffmpeg_ms = round((time.monotonic() - started) * 1000)
    frames = wav_frames(out)
    if frames != expected:
        raise RuntimeError(f"Segment has {frames} samples, expected {expected}")
    hash_started = time.monotonic()
    output = _output(ctx, out, frames)
    ctx.set_progress(100)
    return {"outputs": [output], "metrics": {
        "ffmpegMs": ffmpeg_ms, "hashMs": round((time.monotonic() - hash_started) * 1000),
        "segmentMs": end_ms - start_ms, "outputBytes": output["sizeBytes"],
        "realtimeFactor": round((end_ms - start_ms) / max(1, ffmpeg_ms), 1)}}


# ----------------------------------------------------------------------------------------------- merge

def merge_audio(ctx: AttemptContext) -> dict[str, Any]:
    sample_rate = int(ctx.input["sampleRate"])
    expected = int(ctx.input["expectedSamples"])
    segments = [ctx.resolve(p) for p in ctx.input["segmentPaths"]]
    out = ctx.work_dir / "audio.wav"
    started = time.monotonic()
    total = 0
    with wave.open(str(out), "wb") as writer:
        writer.setnchannels(CHANNELS)
        writer.setsampwidth(SAMPLE_WIDTH)
        writer.setframerate(sample_rate)
        for path in segments:
            with wave.open(str(path), "rb") as reader:
                if (reader.getnchannels(), reader.getsampwidth(), reader.getframerate()) != (
                        CHANNELS, SAMPLE_WIDTH, sample_rate):
                    raise RuntimeError(f"Segment {path.name} has an unexpected audio format")
                while True:
                    ctx.check_cancelled()
                    data = reader.readframes(1 << 20)
                    if not data:
                        break
                    writer.writeframesraw(data)
                    total += len(data) // (CHANNELS * SAMPLE_WIDTH)
                    ctx.set_progress(min(99.0, total / max(1, expected) * 100))
    merge_ms = round((time.monotonic() - started) * 1000)
    if total != expected or wav_frames(out) != expected:
        raise RuntimeError(f"Merged audio has {total} samples, expected {expected}")
    hash_started = time.monotonic()
    output = _output(ctx, out, total)
    ctx.set_progress(100)
    return {"outputs": [output], "metrics": {
        "mergeMs": merge_ms, "hashMs": round((time.monotonic() - hash_started) * 1000),
        "segments": len(segments), "outputBytes": output["sizeBytes"]}}


HANDLERS = {"INSPECT_MEDIA": inspect, "EXTRACT_AUDIO": extract_segment, "MERGE_AUDIO": merge_audio}
