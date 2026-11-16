"""Cross-chunk speaker reconciliation (verified in M3a): must-link from co-activity in chunk overlaps, cannot-link
within a chunk, then average-linkage clustering on cosine distance below a threshold.

Times are integer milliseconds. Centroids are plain float lists; this module never logs or prints them.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Any

MUST_LINK_MIN_MS = 2_000
MUST_LINK_MIN_SHARE = 0.7
TURN_JOIN_GAP_MS = 200


@dataclass
class Chunk:
    index: int
    start_ms: int
    end_ms: int
    turns: list[dict[str, Any]]                       # exclusive turns {"s","e","spk"} in absolute ms
    centroids: dict[str, list[float]] = field(default_factory=dict)


@dataclass
class Reconciliation:
    mapping: dict[tuple[int, str], str]               # (chunk index, local label) -> global code
    must_links: int
    refused_must_links: int
    embedding_merges: list[float]
    global_turns: list[dict[str, Any]]
    speakers: list[dict[str, Any]]                    # [{"code", "speechMs", "turns", "firstMs"}]


def cosine_distance(a: list[float], b: list[float]) -> float:
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return math.inf
    return 1.0 - dot / (na * nb)


def _speech(turns: list[dict[str, Any]], spk: str, lo: int, hi: int) -> list[tuple[int, int]]:
    return [(max(t["s"], lo), min(t["e"], hi)) for t in turns if t["spk"] == spk and t["e"] > lo and t["s"] < hi]


def core_bounds(chunks: list[Chunk], i: int) -> tuple[int, int]:
    """The part of chunk i that it owns: half of each overlap goes to each neighbour."""
    c = chunks[i]
    lo = c.start_ms if i == 0 else c.start_ms + (chunks[i - 1].end_ms - c.start_ms) // 2
    hi = c.end_ms if i == len(chunks) - 1 else c.end_ms - (c.end_ms - chunks[i + 1].start_ms) // 2
    return lo, hi


def reconcile(chunks: list[Chunk], threshold: float) -> Reconciliation:
    chunks = sorted(chunks, key=lambda c: c.index)
    items = [(c.index, spk) for c in chunks for spk in sorted({t["spk"] for t in c.turns})]
    parent = {it: it for it in items}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def chunk_set(root):
        return {c for (c, s) in items if find((c, s)) == root}

    must, refused = 0, 0
    for i in range(len(chunks) - 1):
        a, b = chunks[i], chunks[i + 1]
        lo, hi = b.start_ms, a.end_ms
        if hi <= lo:
            continue
        pairs = []
        for sa in sorted({t["spk"] for t in a.turns}):
            ra = _speech(a.turns, sa, lo, hi)
            da = sum(e - s for s, e in ra)
            for sb in sorted({t["spk"] for t in b.turns}):
                rb = _speech(b.turns, sb, lo, hi)
                db = sum(e - s for s, e in rb)
                inter = sum(max(0, min(e1, e2) - max(s1, s2)) for s1, e1 in ra for s2, e2 in rb)
                if inter >= MUST_LINK_MIN_MS and inter / max(min(da, db), 1) >= MUST_LINK_MIN_SHARE:
                    pairs.append((inter, sa, sb))
        for _, sa, sb in sorted(pairs, reverse=True):
            ra, rb = find((a.index, sa)), find((b.index, sb))
            if ra == rb:
                continue
            if chunk_set(ra) & chunk_set(rb):
                refused += 1
                continue
            parent[rb] = ra
            must += 1

    groups: dict[tuple[int, str], list[tuple[int, str]]] = {}
    for it in items:
        groups.setdefault(find(it), []).append(it)
    clusters = list(groups.values())
    centroid = {(c.index, spk): v for c in chunks for spk, v in c.centroids.items()}

    def dist(x, y):
        ds = [cosine_distance(centroid[p], centroid[q]) for p in x for q in y if p in centroid and q in centroid]
        return sum(ds) / len(ds) if ds else math.inf

    merges: list[float] = []
    while True:
        best = None
        for i in range(len(clusters)):
            for j in range(i + 1, len(clusters)):
                if {c for c, _ in clusters[i]} & {c for c, _ in clusters[j]}:
                    continue  # cannot-link: two speakers of the same chunk are different people
                d = dist(clusters[i], clusters[j])
                if best is None or d < best[0]:
                    best = (d, i, j)
        if best is None or best[0] >= threshold:
            break
        merges.append(round(best[0], 3))
        _, i, j = best
        clusters[i] = clusters[i] + clusters[j]
        clusters.pop(j)

    # Global turns: each chunk contributes the part it owns.
    local_of: dict[tuple[int, str], int] = {}
    for k, cl in enumerate(clusters):
        for it in cl:
            local_of[it] = k
    raw: list[dict[str, Any]] = []
    for i, c in enumerate(chunks):
        lo, hi = core_bounds(chunks, i)
        for t in c.turns:
            s, e = max(t["s"], lo), min(t["e"], hi)
            if e > s:
                raw.append({"s": s, "e": e, "cluster": local_of[(c.index, t["spk"])]})
    raw.sort(key=lambda t: (t["s"], t["e"]))
    first_seen: dict[int, int] = {}
    for t in raw:
        first_seen.setdefault(t["cluster"], t["s"])
    order = sorted(range(len(clusters)), key=lambda k: first_seen.get(k, math.inf))
    code = {k: f"SPK_{n + 1:02d}" for n, k in enumerate(order)}
    turns: list[dict[str, Any]] = []
    for t in raw:
        spk = code[t["cluster"]]
        if turns and turns[-1]["spk"] == spk and t["s"] - turns[-1]["e"] <= TURN_JOIN_GAP_MS:
            turns[-1]["e"] = max(turns[-1]["e"], t["e"])
        else:
            turns.append({"s": t["s"], "e": t["e"], "spk": spk})
    speakers = []
    for k in order:
        if k not in first_seen:
            continue  # a speaker heard only inside overlaps owned by a neighbour
        c = code[k]
        mine = [t for t in turns if t["spk"] == c]
        speakers.append({"code": c, "speechMs": sum(t["e"] - t["s"] for t in mine), "turns": len(mine),
                         "firstMs": first_seen[k]})
    mapping = {it: code[local_of[it]] for it in items}
    return Reconciliation(mapping, must, refused, merges, turns, speakers)


def global_centroids(chunks: list[Chunk], rec: Reconciliation) -> dict[str, list[float]]:
    """Speech-time-weighted mean of the chunk centroids of each global speaker (used only for later suggestions)."""
    sums: dict[str, list[float]] = {}
    weights: dict[str, float] = {}
    for c in chunks:
        for spk, vec in c.centroids.items():
            g = rec.mapping.get((c.index, spk))
            if g is None:
                continue
            w = float(sum(t["e"] - t["s"] for t in c.turns if t["spk"] == spk)) or 1.0
            acc = sums.setdefault(g, [0.0] * len(vec))
            for i, x in enumerate(vec):
                acc[i] += w * x
            weights[g] = weights.get(g, 0.0) + w
    return {g: [x / weights[g] for x in acc] for g, acc in sums.items()}


def choose_samples(turns: list[dict[str, Any]], code: str, count: int = 3, min_ms: int = 1_500,
                   max_ms: int = 6_000) -> list[tuple[int, int]]:
    """The `count` longest turns of a speaker (at least min_ms), clipped to max_ms, in time order."""
    candidates = sorted((t for t in turns if t["spk"] == code and t["e"] - t["s"] >= min_ms),
                        key=lambda t: t["e"] - t["s"], reverse=True)[:count]
    return sorted((t["s"], min(t["e"], t["s"] + max_ms)) for t in candidates)
