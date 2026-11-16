"""Pure-logic tests for the speech pipeline (no models, no numpy)."""

from __future__ import annotations

import copy

from khmerdub_ai.speechlogic import dialogue
from khmerdub_ai.speechlogic.speakers import Chunk, choose_samples, core_bounds, global_centroids, reconcile
from khmerdub_ai.speechlogic.timing import merge_regions, pick_windows, repair_first_words, snap_boundary


# ------------------------------------------------------------------------------------------------- timing

def test_merge_regions_joins_overlaps_and_small_gaps():
    assert merge_regions([[500, 900], [0, 100], [80, 200], [915, 1000]], join_gap_ms=20) == [[0, 200], [500, 1000]]


def test_snap_boundary_picks_middle_of_longest_silence_near_target():
    regions = [[0, 290_000], [292_000, 299_000], [305_000, 400_000]]
    assert snap_boundary(regions, 300_000, 30_000) == 302_000          # the 6 s gap beats the 2 s gap
    assert snap_boundary([[0, 1_000_000]], 300_000, 30_000) == 300_000  # no silence: keep the target


def test_pick_windows_prefers_speech():
    regions = [[100_000, 160_000], [400_000, 430_000]]
    starts = pick_windows(regions, 600_000, 2)
    assert len(starts) == 2 and 95_000 <= starts[0] <= 130_000 and 395_000 <= starts[1] <= 400_000


def test_first_word_repair_m3a_case():
    # clip1 309.75-322.06: "这" pinned at the region start, the rest of the line 11 s later.
    seg = {"start": 309.75, "end": 322.06, "words": [
        {"w": "这", "s": 309.75, "e": 309.99, "p": 0.4}, {"w": "不是", "s": 321.16, "e": 321.32, "p": 0.9},
        {"w": "裴", "s": 321.32, "e": 321.48, "p": 0.9}]}
    normal = {"start": 1.0, "end": 2.0, "words": [{"w": "你", "s": 1.0, "e": 1.2, "p": 0.9},
                                                    {"w": "好", "s": 1.3, "e": 1.5, "p": 0.9}]}
    long_first = {"start": 6.83, "end": 7.33, "words": [{"w": "小", "s": 0.5, "e": 6.0, "p": 0.6}]}
    segs = [seg, copy.deepcopy(normal), long_first]
    assert repair_first_words(segs) == 2
    assert segs[0]["words"][0]["e"] == 321.16 and 320.8 < segs[0]["words"][0]["s"] < 321.16
    assert segs[0]["start"] == segs[0]["words"][0]["s"] and segs[0]["words"][0]["retimed"] is True
    assert segs[1] == normal
    assert segs[2]["words"][0]["e"] == 6.0 and segs[2]["words"][0]["s"] >= 5.4


# ------------------------------------------------------------------------------------------- speakers

def _chunk(i, s, e, turns, cents):
    return Chunk(i, s, e, [{"s": a, "e": b, "spk": spk} for a, b, spk in turns], cents)


def test_reconcile_must_link_across_overlap_and_cannot_link_within_chunk():
    a, b = [1.0, 0.0, 0.0], [0.0, 1.0, 0.0]
    chunks = [
        _chunk(0, 0, 600_000, [(0, 100_000, "A0"), (550_000, 590_000, "A0"), (200_000, 300_000, "B0")],
               {"A0": a, "B0": a}),             # identical centroids but same chunk: must stay separate
        _chunk(1, 540_000, 1_140_000, [(550_000, 590_000, "X1"), (700_000, 800_000, "Y1")], {"X1": a, "Y1": b}),
    ]
    rec = reconcile(chunks, threshold=0.5)
    assert rec.mapping[(0, "A0")] == rec.mapping[(1, "X1")]   # talks at the same times in the overlap
    assert rec.mapping[(0, "A0")] != rec.mapping[(0, "B0")]   # cannot-link wins over identical embeddings
    assert rec.must_links == 1
    assert [s["code"] for s in rec.speakers] == ["SPK_01", "SPK_02", "SPK_03"]


def test_reconcile_embedding_merge_for_speaker_absent_from_overlap():
    v = [0.6, 0.8, 0.0]
    chunks = [_chunk(0, 0, 600_000, [(10_000, 50_000, "S")], {"S": v}),
              _chunk(1, 540_000, 1_140_000, [(900_000, 950_000, "T")], {"T": [0.61, 0.79, 0.0]})]
    assert len({*reconcile(chunks, 0.5).mapping.values()}) == 1
    assert len({*reconcile(chunks, 0.00001).mapping.values()}) == 2


def test_core_bounds_split_each_overlap_in_half():
    chunks = [Chunk(0, 0, 600_000, []), Chunk(1, 540_000, 1_140_000, []), Chunk(2, 1_080_000, 1_200_000, [])]
    assert core_bounds(chunks, 0) == (0, 570_000)
    assert core_bounds(chunks, 1) == (570_000, 1_110_000)
    assert core_bounds(chunks, 2) == (1_110_000, 1_200_000)


def test_global_turns_and_samples():
    chunks = [_chunk(0, 0, 600_000, [(0, 10_000, "A"), (560_000, 590_000, "A")], {"A": [1.0, 0.0]}),
              _chunk(1, 540_000, 1_140_000, [(560_000, 590_000, "B"), (600_000, 603_000, "B")], {"B": [1.0, 0.0]})]
    rec = reconcile(chunks, 0.5)
    # 560-590 s comes from chunk 0's core (overlap split at 570 s) and chunk 1's core; the halves rejoin. The turn at
    # 600 s is 10 s later, far beyond the 200 ms join gap, so it stays separate.
    assert rec.global_turns == [{"s": 0, "e": 10_000, "spk": "SPK_01"}, {"s": 560_000, "e": 590_000, "spk": "SPK_01"},
                                {"s": 600_000, "e": 603_000, "spk": "SPK_01"}]
    assert choose_samples(rec.global_turns, "SPK_01", count=3) == [(0, 6_000), (560_000, 566_000), (600_000, 603_000)]
    assert choose_samples(rec.global_turns, "SPK_01", count=3, min_ms=5_000) == [(0, 6_000), (560_000, 566_000)]
    assert set(global_centroids(chunks, rec)) == {"SPK_01"}


# -------------------------------------------------------------------------------------------- dialogue

def _w(text, s, e, p=0.9):
    return {"w": text, "s": s, "e": e, "p": p}


def test_overlap_words_are_emitted_once_and_cut_lines_rejoined():
    # chunk 0 core [0, 300 s), chunk 1 core [300 s, 600 s); both decode the padded overlap 297.5-302.5 s.
    line_across = [_w("我", 299.0, 299.4), _w("们", 299.4, 299.8), _w("走", 300.2, 300.6), _w("吧", 300.6, 301.0)]
    c0 = {"chunkIndex": 0, "coreStartMs": 0, "coreEndMs": 300_000, "segments": [
        {"start": 10.0, "end": 11.0, "text": "妈妈", "avgLogprob": -0.2, "words": [_w("妈", 10.0, 10.3), _w("妈", 10.3, 10.6)]},
        {"start": 299.0, "end": 301.0, "text": "我们走吧", "avgLogprob": -0.3, "words": copy.deepcopy(line_across)}]}
    c1 = {"chunkIndex": 1, "coreStartMs": 300_000, "coreEndMs": 600_000, "segments": [
        {"start": 299.0, "end": 301.0, "text": "我们走吧", "avgLogprob": -0.3, "words": copy.deepcopy(line_across)},
        {"start": 400.0, "end": 401.0, "text": "好", "avgLogprob": -0.1, "words": [_w("好", 400.0, 400.5)]}]}
    turns = [{"s": 9_000, "e": 12_000, "spk": "SPK_01"}, {"s": 298_000, "e": 302_000, "spk": "SPK_02"},
             {"s": 399_000, "e": 402_000, "spk": "SPK_01"}]
    regions = [[10_000, 10_600], [299_000, 301_000], [400_000, 400_500], [500_000, 503_000]]
    out = dialogue.build([c1, c0], turns, regions)
    texts = [ln["text"] for ln in out["lines"]]
    assert texts == ["妈妈", "我们走吧", "好"]
    joined = out["lines"][1]
    assert joined["speaker"] == "SPK_02" and joined["purity"] == 1.0 and joined["flags"].get("joinedAcrossChunks")
    assert out["summary"]["duplicateWords"] == 0          # 妈妈 inside one chunk is not a duplicate
    assert out["summary"]["words"] == 7
    assert out["uncovered"] == [[500_000, 503_000]]       # speech nobody transcribed is reported
    assert [ln["seq"] for ln in out["lines"]] == [0, 1, 2]


def test_duplicate_detection_flags_words_emitted_by_two_chunks():
    seg = {"start": 1.0, "end": 1.5, "text": "好", "avgLogprob": -0.1, "words": [_w("好", 1.0, 1.4)]}
    c0 = {"chunkIndex": 0, "coreStartMs": 0, "coreEndMs": 1_300, "segments": [copy.deepcopy(seg)]}
    c1 = {"chunkIndex": 1, "coreStartMs": 1_100, "coreEndMs": 3_000, "segments": [copy.deepcopy(seg)]}  # broken plan
    assert dialogue.build([c0, c1], None, [])["summary"]["duplicateWords"] == 1


def test_mixed_speaker_line_is_flagged_and_unknown_speaker_allowed():
    c0 = {"chunkIndex": 0, "coreStartMs": 0, "coreEndMs": 100_000, "segments": [
        {"start": 1.0, "end": 4.0, "text": "abcd", "avgLogprob": -1.5,
         "words": [_w("a", 1.0, 1.5, 0.3), _w("b", 1.5, 2.0, 0.3), _w("c", 2.0, 2.5, 0.3), _w("d", 3.0, 4.0, 0.3)]},
        {"start": 50.0, "end": 51.0, "text": "z", "avgLogprob": -0.1, "words": [_w("z", 50.0, 50.5)]}]}
    turns = [{"s": 1_000, "e": 2_000, "spk": "SPK_01"}, {"s": 2_000, "e": 4_000, "spk": "SPK_02"}]
    out = dialogue.build([c0], turns, [])
    first, second = out["lines"]
    assert first["flags"]["mixedSpeakers"] and first["flags"]["lowConfidence"] and first["purity"] < 0.8
    assert second["speaker"] is None and second["flags"]["noSpeaker"]
    assert out["summary"]["linesWithoutSpeaker"] == 1
