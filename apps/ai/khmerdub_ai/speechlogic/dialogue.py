"""BUILD_DIALOGUE: join transcript chunks and global speaker turns into dialogue lines.

Rules:
  * Word ownership: a word belongs to the chunk whose core [coreStart, coreEnd) contains its midpoint, so a word in
    an overlap is emitted exactly once. (Cores are contiguous; padding only gives the model context.)
  * A line cut by a core boundary is re-joined with its continuation from the next chunk.
  * Each word gets the speaker active at its midpoint (or the nearest turn within 500 ms); a line gets the majority
    speaker by word duration, with purity = that speaker's share.
Times: transcript words are seconds; turns, regions and lines are integer milliseconds.
"""

from __future__ import annotations

from typing import Any

NEAREST_TURN_MS = 500
JOIN_GAP_MS = 800
MIXED_PURITY = 0.8
LOW_CONFIDENCE_WORD_P = 0.5
LOW_CONFIDENCE_LOGPROB = -1.0
DUPLICATE_WINDOW_MS = 300
COVERAGE_MIN_REGION_MS = 1_000


def _ms(seconds: float) -> int:
    return int(round(seconds * 1000))


def owned_segments(chunk: dict[str, Any], is_last: bool) -> list[dict[str, Any]]:
    """Segments of one transcript chunk restricted to the words this chunk owns."""
    core_s, core_e = int(chunk["coreStartMs"]), int(chunk["coreEndMs"])
    out = []
    for seg in chunk["segments"]:
        words = seg.get("words") or []

        def mine(start: float, end: float) -> bool:
            mid = (_ms(start) + _ms(end)) / 2
            return core_s <= mid < core_e or (is_last and mid == core_e)

        if not words:
            if mine(seg["start"], seg["end"]) and seg.get("text", "").strip():
                out.append({**seg, "words": [], "cutStart": False, "cutEnd": False, "chunk": chunk["chunkIndex"]})
            continue
        idx = [k for k, w in enumerate(words) if mine(w["s"], w["e"])]
        if not idx:
            continue
        kept = [{**words[k], "_c": chunk["chunkIndex"]} for k in idx]
        out.append({**seg, "words": kept, "cutStart": idx[0] > 0, "cutEnd": idx[-1] < len(words) - 1,
                    "chunk": chunk["chunkIndex"]})
    return out


def _text(seg: dict[str, Any]) -> str:
    if not seg["cutStart"] and not seg["cutEnd"] and seg.get("text"):
        return seg["text"].strip()
    return "".join(w["w"] for w in seg["words"]).strip()


def _speaker_at(turns: list[dict[str, Any]], t_ms: float) -> str | None:
    best, best_d = None, NEAREST_TURN_MS + 1
    for turn in turns:
        if turn["s"] <= t_ms < turn["e"]:
            return turn["spk"]
        d = min(abs(turn["s"] - t_ms), abs(turn["e"] - t_ms))
        if d < best_d:
            best, best_d = turn["spk"], d
    return best if best_d <= NEAREST_TURN_MS else None


def build(transcripts: list[dict[str, Any]], turns: list[dict[str, Any]] | None,
          speech_regions: list[list[int]]) -> dict[str, Any]:
    transcripts = sorted(transcripts, key=lambda c: c["chunkIndex"])
    segs: list[dict[str, Any]] = []
    for i, chunk in enumerate(transcripts):
        segs.extend(owned_segments(chunk, i == len(transcripts) - 1))
    segs.sort(key=lambda s: (_ms(s["words"][0]["s"]) if s["words"] else _ms(s["start"])))

    # Re-join lines split by a core boundary.
    joined: list[dict[str, Any]] = []
    for seg in segs:
        prev = joined[-1] if joined else None
        if (prev and prev["cutEnd"] and seg["cutStart"] and prev["chunk"] != seg["chunk"] and prev["words"]
                and seg["words"] and _ms(seg["words"][0]["s"]) - _ms(prev["words"][-1]["e"]) <= JOIN_GAP_MS):
            prev["words"] = prev["words"] + seg["words"]
            prev["cutEnd"] = seg["cutEnd"]
            prev["joined"] = True
            prev["avgLogprob"] = min(prev.get("avgLogprob", 0.0), seg.get("avgLogprob", 0.0))
            prev["text"] = ""
            continue
        joined.append(dict(seg))

    sorted_turns = sorted(turns or [], key=lambda t: t["s"])
    lines: list[dict[str, Any]] = []
    for seg in joined:
        words = seg["words"]
        start = _ms(words[0]["s"]) if words else _ms(seg["start"])
        end = _ms(words[-1]["e"]) if words else _ms(seg["end"])
        end = max(end, start + 10)
        votes: dict[str, float] = {}
        if turns is None:
            votes["SPK_01"] = 1.0
        else:
            for w in words:
                spk = _speaker_at(sorted_turns, (_ms(w["s"]) + _ms(w["e"])) / 2)
                if spk:
                    votes[spk] = votes.get(spk, 0.0) + max(_ms(w["e"]) - _ms(w["s"]), 10)
            if not words:
                spk = _speaker_at(sorted_turns, (start + end) / 2)
                if spk:
                    votes[spk] = 1.0
        speaker = max(votes, key=votes.get) if votes else None
        purity = round(votes[speaker] / sum(votes.values()), 3) if speaker else None
        probs = [w.get("p", 1.0) for w in words]
        mean_p = sum(probs) / len(probs) if probs else 1.0
        flags = {}
        if mean_p < LOW_CONFIDENCE_WORD_P or seg.get("avgLogprob", 0.0) < LOW_CONFIDENCE_LOGPROB:
            flags["lowConfidence"] = True
        if purity is not None and purity < MIXED_PURITY:
            flags["mixedSpeakers"] = True
        if speaker is None:
            flags["noSpeaker"] = True
        if any(w.get("retimed") for w in words):
            flags["firstWordRetimed"] = True
        if seg.get("joined"):
            flags["joinedAcrossChunks"] = True
        lines.append({"startMs": start, "endMs": end, "speaker": speaker, "purity": purity, "text": _text(seg),
                      "avgLogprob": seg.get("avgLogprob"),
                      "words": [{"w": w["w"], "s": _ms(w["s"]), "e": _ms(w["e"]), "p": w.get("p"),
                                 **({"retimed": True} if w.get("retimed") else {})} for w in words],
                      "_chunks": [w.get("_c") for w in words], "flags": flags})
    lines = [ln for ln in lines if ln["text"]]
    lines.sort(key=lambda ln: (ln["startMs"], ln["endMs"]))
    for n, ln in enumerate(lines):
        ln["seq"] = n

    # Checks used by the chunk-consistency verification. A duplicate is the same word emitted by two DIFFERENT
    # chunks within 300 ms (repeated characters such as 妈妈 inside one chunk are legitimate).
    emitted = sorted(((w["s"], w["w"].strip(), c) for ln in lines for w, c in zip(ln["words"], ln["_chunks"])),
                     key=lambda x: x[0])
    for ln in lines:
        del ln["_chunks"]
    duplicates = 0
    for k in range(1, len(emitted)):
        j = k - 1
        while j >= 0 and emitted[k][0] - emitted[j][0] <= DUPLICATE_WINDOW_MS:
            if emitted[j][1] == emitted[k][1] and emitted[j][1] and emitted[j][2] != emitted[k][2]:
                duplicates += 1
                break
            j -= 1
    spans = [(ln["startMs"], ln["endMs"]) for ln in lines]
    uncovered = [[s, e] for s, e in speech_regions if e - s >= COVERAGE_MIN_REGION_MS
                 and not any(min(e, le) > max(s, ls) for ls, le in spans)]

    speakers: dict[str, dict[str, Any]] = {}
    for t in sorted_turns:
        sp = speakers.setdefault(t["spk"], {"code": t["spk"], "speechMs": 0, "lines": 0, "firstMs": t["s"]})
        sp["speechMs"] += t["e"] - t["s"]
    for ln in lines:
        if ln["speaker"]:
            sp = speakers.setdefault(ln["speaker"], {"code": ln["speaker"], "speechMs": 0, "lines": 0,
                                                     "firstMs": ln["startMs"]})
            sp["lines"] += 1
    ordered = sorted(speakers.values(), key=lambda s: s["firstMs"])
    for n, sp in enumerate(ordered):
        sp["displayOrder"] = n
    summary = {"lines": len(lines), "speakers": len(ordered), "words": len(emitted), "duplicateWords": duplicates,
               "uncoveredSpeechRegions": len(uncovered),
               "mixedLines": sum(1 for ln in lines if ln["flags"].get("mixedSpeakers")),
               "lowConfidenceLines": sum(1 for ln in lines if ln["flags"].get("lowConfidence")),
               "retimedLines": sum(1 for ln in lines if ln["flags"].get("firstWordRetimed")),
               "joinedLines": sum(1 for ln in lines if ln["flags"].get("joinedAcrossChunks")),
               "linesWithoutSpeaker": sum(1 for ln in lines if ln["speaker"] is None)}
    return {"format": "khmerdub.dialogue.v1", "speakers": ordered, "lines": lines, "summary": summary,
            "uncovered": uncovered[:200]}
