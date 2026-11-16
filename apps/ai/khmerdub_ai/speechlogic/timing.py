"""Timing rules: speech-region merging, language-detection window choice, first-word repair."""

from __future__ import annotations

from typing import Any

# First-word repair (M3a finding): after a long non-speech stretch faster-whisper can pin the first word to the start
# of the VAD region while the rest of the line starts 10-14 s later (6 cases in two 7-minute clips).
FIRST_WORD_MAX_GAP_S = 1.0
FIRST_WORD_MAX_DURATION_S = 2.0
FIRST_WORD_SECONDS_PER_CHAR = 0.3
FIRST_WORD_MAX_LENGTH_S = 0.6


def merge_regions(regions: list[list[int]], join_gap_ms: int = 0) -> list[list[int]]:
    """Sort and merge overlapping or nearly adjacent [start, end] ms regions."""
    merged: list[list[int]] = []
    for s, e in sorted([int(a), int(b)] for a, b in regions):
        if merged and s <= merged[-1][1] + join_gap_ms:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])
    return merged


def speech_in(regions: list[list[int]], start: int, end: int) -> int:
    return sum(max(0, min(e, end) - max(s, start)) for s, e in regions)


def pick_windows(regions: list[list[int]], duration_ms: int, count: int, window_ms: int = 30_000) -> list[int]:
    """Start times of up to `count` windows spread over the movie, each placed where speech is densest."""
    if duration_ms <= window_ms:
        return [0]
    starts: list[int] = []
    span = duration_ms / count
    for i in range(count):
        lo = int(i * span)
        hi = int(min(duration_ms - window_ms, (i + 1) * span - window_ms / 2))
        best, best_speech = lo, 0
        for candidate in range(lo, max(lo, hi) + 1, 5_000):
            speech = speech_in(regions, candidate, candidate + window_ms)
            if speech > best_speech:
                best, best_speech = candidate, speech
        if best_speech > 0:
            starts.append(best)
    return starts or [0]


def repair_first_words(segments: list[dict[str, Any]]) -> int:
    """Re-time a first word that was pinned far before the rest of its line. Returns the number repaired.

    Words are {"w", "s", "e", "p"} in seconds. Rules:
      * gap between word 1 and word 2 > FIRST_WORD_MAX_GAP_S -> word 1 ends where word 2 starts;
      * a single first word lasting > FIRST_WORD_MAX_DURATION_S -> it keeps its end and gets a plausible length.
    The segment start follows word 1. Repaired words are marked "retimed": true.
    """
    repaired = 0
    for seg in segments:
        words = seg.get("words") or []
        if not words:
            continue
        first = words[0]
        duration = first["e"] - first["s"]
        plausible = min(FIRST_WORD_MAX_LENGTH_S, max(0.1, FIRST_WORD_SECONDS_PER_CHAR * len(first["w"].strip() or "x")))
        if len(words) >= 2 and words[1]["s"] - first["e"] > FIRST_WORD_MAX_GAP_S:
            end = words[1]["s"]
            length = min(plausible, max(0.1, duration))
        elif duration > FIRST_WORD_MAX_DURATION_S:
            end = first["e"]
            length = plausible
        else:
            continue
        first["s"], first["e"], first["retimed"] = round(end - length, 3), round(end, 3), True
        seg["start"] = first["s"]
        seg["firstWordRetimed"] = True
        repaired += 1
    return repaired


def snap_boundary(regions: list[list[int]], target_ms: int, search_ms: int) -> int:
    """Best cut point near target: the middle of the longest silence within +/- search_ms (target if none)."""
    lo, hi = target_ms - search_ms, target_ms + search_ms
    best, best_len = target_ms, -1
    previous_end = None
    for s, e in regions:
        if previous_end is not None:
            gap_s, gap_e = max(previous_end, lo), min(s, hi)
            if gap_e > gap_s and gap_e - gap_s > best_len:
                best, best_len = (gap_s + gap_e) // 2, gap_e - gap_s
        previous_end = e
    return best
