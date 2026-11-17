"""M3b-2 whole-stack review scenarios (S18-S22) on a real speech clip, against the real API, AI service and worker.

Run with the apps/ai venv Python:  python tests/e2e/m3b2_scenarios.py --clip <movie> [--only S18,S19]
S18 imports and processes the clip; S19-S22 continue on S18's project (S21 adds a second revision by processing
again). Keep-awake evidence comes from `powercfg /requests` (no admin rights needed), as in S11-S17.
Point it at an isolated test stack with the variables described in stack.py (KHMERDUB_E2E_ISOLATED=1 checks them).
Test projects are archived afterwards (never deleted).
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import subprocess
import sys
import time
import traceback
import uuid
from collections import Counter
from pathlib import Path
from typing import Any

import stack
from m3b_scenarios import CREATED, _assets_of, audio_ready_project, keep_awake_held, revisions, transcribe, wait_keep_awake

SEEN: list[str] = []          # every API response body of the run, scanned for private data in S22
REVIEW_KEEP_AWAKE: list[bool] = []  # keep-awake samples taken while only reviewing (no processing)
STATE: dict[str, Any] = {}


# ------------------------------------------------------------------------------------------------- helpers

def call(method: str, path: str, expect: int, **kw: Any) -> Any:
    r = stack.http.request(method, path, **kw)
    if "json" in r.headers.get("content-type", ""):
        SEEN.append(r.text)
    assert r.status_code == expect, f"{method} {path}: expected {expect}, got {r.status_code}: {r.text[:400]}"
    return r.json() if "json" in r.headers.get("content-type", "") and r.content else r


def code_of(method: str, path: str, expect: int, **kw: Any) -> str:
    return call(method, path, expect, **kw).get("code")


def sample_keep_awake() -> None:
    REVIEW_KEEP_AWAKE.append(keep_awake_held())


def page(pid: str, rev: str | None = None) -> dict[str, Any]:
    params: dict[str, Any] = {"limit": 1000}
    if rev:
        params["revisionId"] = rev
    return call("GET", f"/api/projects/{pid}/dialogue", 200, params=params)


def speakers(pid: str, rev: str | None = None) -> list[dict[str, Any]]:
    return call("GET", f"/api/projects/{pid}/speakers", 200, params={"revisionId": rev} if rev else None)


def line(pid: str, line_id: str, rev: str | None = None) -> dict[str, Any]:
    return next(x for x in page(pid, rev)["lines"] if x["id"] == line_id)


def export(pid: str, rev: str | None = None) -> str:
    """Everything a user can see in a revision, without versions (which keep growing by design)."""
    lines = [{k: v for k, v in x.items() if k != "version"} for x in page(pid, rev)["lines"]]
    spk = [{k: v for k, v in s.items() if k != "version"} for s in speakers(pid, rev)]
    return json.dumps({"lines": lines, "speakers": sorted(spk, key=lambda s: s["code"])}, sort_keys=True, ensure_ascii=False)


def versions(pid: str, rev: str | None = None) -> dict[str, int]:
    out = {x["id"]: x["version"] for x in page(pid, rev)["lines"]}
    out.update({s["id"]: s["version"] for s in speakers(pid, rev)})
    return out


def operations(pid: str) -> list[dict[str, Any]]:
    return call("GET", f"/api/projects/{pid}/review-operations", 200, params={"limit": 200})


def latest_undoable(pid: str) -> dict[str, Any] | None:
    return next((o for o in operations(pid) if o["undoable"]), None)


def undo(pid: str, op_id: str, expect: int = 200) -> Any:
    return call("POST", f"/api/projects/{pid}/review-operations/{op_id}/undo", expect)


def patch_line(pid: str, ln: dict[str, Any], expect: int = 200, **fields: Any) -> Any:
    return call("PATCH", f"/api/projects/{pid}/segments/{ln['id']}", expect, json={"version": ln["version"], **fields})


def patch_speaker(pid: str, s: dict[str, Any], expect: int = 200, **fields: Any) -> Any:
    return call("PATCH", f"/api/projects/{pid}/speakers/{s['id']}", expect, json={"version": s["version"], **fields})


def activate(pid: str, rev: str, confirm: bool = True, expect: int = 200) -> Any:
    return call("POST", f"/api/projects/{pid}/dialogue-revisions/{rev}/activate", expect, json={"confirm": confirm})


def active_rev(pid: str) -> dict[str, Any]:
    act = [r for r in revisions(pid) if r["status"] == "ACTIVE"]
    assert len(act) == 1, f"exactly one active revision expected: {act}"
    return act[0]


def wav_seconds(body: bytes) -> float:
    assert body[:4] == b"RIFF" and body[8:12] == b"WAVE", "not a WAV"
    pos, rate, block, data = 12, None, None, None
    while pos + 8 <= len(body):
        cid, size = body[pos:pos + 4], struct.unpack("<I", body[pos + 4:pos + 8])[0]
        if cid == b"fmt ":
            _, ch, rate, _, block = struct.unpack("<HHIIH", body[pos + 8:pos + 22])
        elif cid == b"data":
            data = size
            break
        pos += 8 + size + (size & 1)
    assert rate and block and data is not None, "incomplete WAV"
    return data / (rate * block)


# ------------------------------------------------------------------------------------------------- scenarios

def s18_import_and_process(clip: Path) -> dict[str, Any]:
    """Import a movie, extract audio, transcribe + identify speakers; keep-awake held while processing, released after."""
    assert not keep_awake_held(), "a keep-awake request is already held before the test (another job running?)"
    pid = audio_ready_project(clip, "S18 review workflow")
    jid = transcribe(pid, "QUALITY")
    held_after = wait_keep_awake(True, 120)
    began = time.monotonic()
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 3600)
    assert j["status"] == "COMPLETED", j.get("errorMessage")
    released_after = wait_keep_awake(False, 60)
    rev = active_rev(pid)
    p = page(pid)
    spk = speakers(pid)
    assert p["total"] >= 10 and len(spk) >= 2, (p["total"], len(spk))
    assert all(x["sourceText"] == "".join(w["w"] for w in x["words"]) for x in p["lines"]), "text/word mismatch"
    ml = call("GET", "/api/system/ml", 200)
    text = json.dumps(ml)
    assert "hf_" not in text and "token" not in text.lower(), "secret-like field in /api/system/ml"
    STATE.update(pid=pid, rev1=rev["id"])
    return {"projectId": pid, "transcribeS": round(time.monotonic() - began), "keepAwakeAcquiredAfterS": held_after,
            "keepAwakeReleasedAfterCompletionS": released_after, "lines": p["total"], "speakers": len(spk),
            "workerReport": {k: ml.get(k) for k in ("workerReportedAt", "workerOk") if k in ml} or sorted(ml)[:8]}


def s19_review_split_merge_undo(clip: Path) -> dict[str, Any]:
    """Speaker review + dialogue edits + line split/merge + speaker merge/split, then undo everything last-first."""
    pid = STATE["pid"]
    before, v_before = export(pid), versions(pid)
    ops_before = len(operations(pid))
    lines = page(pid)["lines"]
    spk = speakers(pid)
    a = next(x for x in lines if len(x["words"]) >= 2)
    s1 = next(s for s in spk if s["id"] == a["speakerId"])
    s2 = next(s for s in spk if s["id"] != s1["id"] and s["segmentCount"] >= 2)

    # Chinese correction + Khmer + approve; a stale save is refused and changes nothing
    edited = patch_line(pid, a, sourceTextEdited=a["sourceText"] + "（改）", editedText="សួស្តី", reviewStatus="APPROVED")
    a1 = edited["lines"][0]
    assert a1["version"] > a["version"] and a1["sourceText"] == a["sourceText"], "version/recognised text"
    assert code_of("PATCH", f"/api/projects/{pid}/segments/{a['id']}", 409, json={"version": a["version"], "editedText": "x"}) == "EDIT_CONFLICT"
    assert line(pid, a["id"])["editedText"] == "សួស្តី", "a stale save changed the line"
    sample_keep_awake()

    # split the line at its first word boundary (it has own text, so both parts are given), then merge it back
    split = call("POST", f"/api/projects/{pid}/segments/{a['id']}/split", 200, json={
        "version": a1["version"], "atWord": 1, "first": {"sourceTextEdited": a1["sourceTextEdited"], "editedText": "សួស្តី"},
        "second": {"sourceTextEdited": "", "editedText": ""}})
    parts = sorted(split["lines"], key=lambda x: x["seq"])
    assert len(parts) == 2 and parts[0]["sourceText"] + parts[1]["sourceText"] == a["sourceText"], "split keeps recognised text"
    merged = call("POST", f"/api/projects/{pid}/segments/merge", 200, json={
        "first": {"id": parts[0]["id"], "version": parts[0]["version"]},
        "second": {"id": parts[1]["id"], "version": parts[1]["version"]}})
    assert merged["lines"][0]["sourceText"] == a["sourceText"], "merge restores the recognised text"
    sample_keep_awake()

    # speakers: name + reviewed, new speaker, manual reassignment, merge speakers, split a speaker
    s1 = next(s for s in speakers(pid) if s["id"] == s1["id"])
    patch_speaker(pid, s1, characterName="林老师", reviewStatus="REVIEWED")
    new = call("POST", f"/api/projects/{pid}/speakers", 200, json={"characterName": "新角色"})["speakers"][0]
    b = next(x for x in page(pid)["lines"] if x["speakerId"] == s1["id"] and x["id"] != merged["lines"][0]["id"])
    call("POST", f"/api/projects/{pid}/segments/reassign", 200,
         json={"speakerId": new["id"], "segments": [{"id": b["id"], "version": b["version"]}]})
    cur = {s["id"]: s for s in speakers(pid)}
    call("POST", f"/api/projects/{pid}/speakers/merge", 200, json={
        "target": {"id": s1["id"], "version": cur[s1["id"]]["version"]},
        "sources": [{"id": new["id"], "version": cur[new["id"]]["version"]}]})
    s2_line = next(x for x in page(pid)["lines"] if x["speakerId"] == s2["id"])
    s2_now = next(s for s in speakers(pid) if s["id"] == s2["id"])
    call("POST", f"/api/projects/{pid}/speakers/{s2['id']}/split", 200, json={
        "version": s2_now["version"], "segments": [{"id": s2_line["id"], "version": s2_line["version"]}],
        "characterName": "分出"})
    sample_keep_awake()

    ops = operations(pid)
    mine = [o for o in ops if not o["undoneAt"]][: len(ops) - ops_before]
    assert len(mine) == 8, [o["type"] for o in mine]
    oldest = mine[-1]
    assert code_of("POST", f"/api/projects/{pid}/review-operations/{oldest['id']}/undo", 409) == "UNDO_ORDER"
    order = []
    while (op := latest_undoable(pid)) and len(order) < len(mine):
        undo(pid, op["id"])
        order.append(op["type"])
    assert order == [o["type"] for o in mine], f"not last-first: {order}"
    assert code_of("POST", f"/api/projects/{pid}/review-operations/{oldest['id']}/undo", 409) == "ALREADY_UNDONE"
    assert export(pid) == before, "undoing everything did not restore the dialogue"
    v_after = versions(pid)
    assert all(v_after[k] >= v for k, v in v_before.items() if k in v_after), "a version went backwards"
    assert v_after[a["id"]] > a1["version"], "line version did not keep growing after undo"
    # ABA: a request based on the version seen after the first edit is still refused after all the undos
    assert code_of("PATCH", f"/api/projects/{pid}/segments/{a['id']}", 409, json={"version": a1["version"], "editedText": "ABA"}) == "EDIT_CONFLICT"
    sample_keep_awake()

    # review work that must survive revision switching later (S21): corrections, Khmer, names, a voice
    lines = page(pid)["lines"]
    for x, km in zip(lines[:2], ("ជំរាបសួរ", "អរគុណ")):
        patch_line(pid, x, sourceTextEdited=x["sourceText"] + "。", editedText=km, reviewStatus="APPROVED")
    s1 = next(s for s in speakers(pid) if s["id"] == s1["id"])
    patch_speaker(pid, s1, characterName="林老师", reviewStatus="REVIEWED")
    voice = call("POST", "/api/voice-profiles", 201, json={"displayName": f"E2E voice {uuid.uuid4().hex[:6]}", "voiceCategory": "ADULT_MALE"})
    s2_now = next(s for s in speakers(pid) if s["id"] == s2["id"])
    patch_speaker(pid, s2_now, voiceProfileId=voice["id"])
    sample_keep_awake()
    return {"changes": [o["type"] for o in reversed(mine)], "undoneLastFirst": order, "outOfOrderUndo": "409 UNDO_ORDER",
            "staleSave": "409 EDIT_CONFLICT", "abaAfterUndo": "409 EDIT_CONFLICT", "restoredExactly": True,
            "versionsMonotonic": True, "keptReviewWork": "2 lines (Chinese+Khmer+approved), 1 named speaker, 1 voice"}


def s20_glossary(clip: Path) -> dict[str, Any]:
    """Glossary: preview, apply a chosen subset (recognised text kept), a stale preview is refused, undo restores."""
    pid = STATE["pid"]
    before = export(pid)
    lines = page(pid)["lines"]
    counts = Counter(w["w"] for x in lines[2:] for w in {w["w"]: w for w in x["words"]}.values()
                     if len(w["w"]) >= 2 and re.fullmatch(r"[一-鿿]+", w["w"]))
    variant = next(w for w, n in counts.most_common() if n >= 2)
    term = "灵犀之言"
    assert term not in json.dumps(lines, ensure_ascii=False)
    body = {"sourceTerm": term, "category": "TERM", "variants": [variant], "khmerTerm": "", "notes": "E2E S20"}
    call("POST", f"/api/projects/{pid}/glossary", 201, json=body)
    assert code_of("POST", f"/api/projects/{pid}/glossary", 409, json=body) == "TERM_EXISTS"
    pv = call("POST", f"/api/projects/{pid}/glossary/preview", 200, json={})
    assert pv["revisionId"] == STATE["rev1"] and len(pv["changes"]) >= 2, len(pv["changes"])
    assert all(term in c["after"] for c in pv["changes"])
    chosen = pv["changes"][-1]
    call("POST", f"/api/projects/{pid}/glossary/apply", 200, json={"token": pv["token"], "segmentIds": [chosen["segmentId"]]})
    got = line(pid, chosen["segmentId"])
    original = next(x for x in lines if x["id"] == chosen["segmentId"])
    assert got["sourceText"] == original["sourceText"] and term in (got["sourceTextEdited"] or ""), got
    others = [c for c in pv["changes"] if c["segmentId"] != chosen["segmentId"]]
    assert all(line(pid, c["segmentId"])["sourceTextEdited"] == next(x for x in lines if x["id"] == c["segmentId"])["sourceTextEdited"]
               for c in others), "lines outside the chosen subset changed"
    sample_keep_awake()
    # stale: preview, then "another tab" edits a line in it, then apply with the old token
    pv2 = call("POST", f"/api/projects/{pid}/glossary/preview", 200, json={})
    victim = line(pid, pv2["changes"][0]["segmentId"])
    patch_line(pid, victim, editedText="另一个标签页")
    assert code_of("POST", f"/api/projects/{pid}/glossary/apply", 409, json={"token": pv2["token"]}) == "PREVIEW_STALE"
    assert all(term not in (line(pid, c["segmentId"])["sourceTextEdited"] or "") for c in pv2["changes"]), "stale preview applied"
    # undo last-first: the other tab's edit, then the glossary apply
    types = []
    for _ in range(2):
        op = latest_undoable(pid)
        undo(pid, op["id"])
        types.append(op["type"])
    assert types == ["EDIT_SEGMENT", "APPLY_GLOSSARY"], types
    assert export(pid) == before, "undo did not restore the dialogue"
    sample_keep_awake()
    return {"variant": variant, "previewLines": len(pv["changes"]), "appliedSubset": 1, "recognisedTextKept": True,
            "duplicateTerm": "409 TERM_EXISTS", "stalePreview": "409 PREVIEW_STALE", "undone": types}


def s21_revisions(clip: Path) -> dict[str, Any]:
    """Process again -> revision 2; a failed backup blocks switching; switch, read-only old one, switch back: nothing lost."""
    pid, rev1 = STATE["pid"], STATE["rev1"]
    work1 = export(pid, rev1)
    ops1 = len(operations(pid))
    r = stack.http.post(f"/api/projects/{pid}/transcribe", json={"mode": "FAST", "language": "auto", "confirmReprocess": False})
    assert r.status_code == 409 and "REPROCESS_CONFIRMATION_REQUIRED" in r.text, r.text
    jid = transcribe(pid, "FAST", confirm=True)
    held = wait_keep_awake(True, 120)
    j = stack.wait_job(jid, {"COMPLETED", "FAILED"}, 3600)
    assert j["status"] == "COMPLETED", j.get("errorMessage")
    released = wait_keep_awake(False, 60)
    rev2 = next(x for x in revisions(pid) if x["status"] == "READY")["id"]
    assert active_rev(pid)["id"] == rev1 and export(pid, rev1) == work1, "processing changed the revision in use"
    backup = stack.asset(j["parameters"]["backupAssetId"])
    assert backup["type"] == "DIALOGUE_BACKUP", backup

    # a failed backup prevents the switch (backup folder blocked by a file; every backup is kept aside, none deleted)
    folder = stack.DATA / "projects" / pid / "dialogue" / "backups"
    aside = folder.with_name("backups.e2e-aside")
    folder.rename(aside)
    folder.write_text("blocks the backup folder (E2E S21)", encoding="utf-8")
    try:
        err = activate(pid, rev2, expect=500)
        assert err["code"] == "BACKUP_FAILED" and "Exception" not in err["detail"] and "/" not in err["detail"], err
        assert active_rev(pid)["id"] == rev1 and export(pid, rev1) == work1, "a failed backup changed something"
    finally:
        folder.rename(folder.with_name(f"backups.e2e-blocker-{int(time.time())}"))
        aside.rename(folder)

    # switch: confirmation first, verified backup, old one read-only (edits and undo)
    asked = activate(pid, rev2, confirm=False, expect=409)
    assert asked["code"] == "ACTIVATION_CONFIRMATION_REQUIRED" and "NOT copied" in asked["detail"], asked
    view = activate(pid, rev2)
    assert view["status"] == "ACTIVE" and view["backupAssetId"], view
    assert stack.asset(view["backupAssetId"])["type"] == "DIALOGUE_BACKUP"
    old_line = page(pid, rev1)["lines"][0]
    assert code_of("PATCH", f"/api/projects/{pid}/segments/{old_line['id']}", 409,
                   json={"version": old_line["version"], "editedText": "x"}) == "REVISION_READ_ONLY"
    assert latest_undoable(pid) is None, "revision 2 has its own (empty) history"
    l2 = page(pid)["lines"][0]
    patch_line(pid, l2, editedText="កំណែទី២")
    sample_keep_awake()

    # switch back: revision 1 exactly as it was (its review work and history), revision 2 keeps its own edit
    back = activate(pid, rev1)
    assert back["status"] == "ACTIVE" and export(pid, rev1) == work1, "review work lost or changed by switching"
    assert len(operations(pid)) == ops1, "review history changed"
    assert line(pid, l2["id"], rev2)["editedText"] == "កំណែទី២", "revision 2 lost its edit"
    l2r = line(pid, l2["id"], rev2)
    assert code_of("PATCH", f"/api/projects/{pid}/segments/{l2['id']}", 409,
                   json={"version": l2r["version"], "editedText": "y"}) == "REVISION_READ_ONLY"
    # editing resumes on revision 1; its undo history continues last-first
    x = page(pid)["lines"][5]
    done = patch_line(pid, x, editedText="បន្ត")
    undo(pid, done["operation"]["id"])
    assert export(pid, rev1) == work1
    sample_keep_awake()

    # repeated switching neither duplicates nor loses anything
    totals = {rev1: page(pid, rev1)["total"], rev2: page(pid, rev2)["total"]}
    spk_counts = {rev1: len(speakers(pid, rev1)), rev2: len(speakers(pid, rev2))}
    for target in (rev2, rev1, rev2, rev1):
        activate(pid, target)
        assert active_rev(pid)["id"] == target
    assert {k: page(pid, k)["total"] for k in totals} == totals and {k: len(speakers(pid, k)) for k in spk_counts} == spk_counts
    assert export(pid, rev1) == work1 and len(operations(pid)) == ops1
    assert line(pid, l2["id"], rev2)["editedText"] == "កំណែទី២"
    STATE["rev2"] = rev2
    sample_keep_awake()
    return {"reprocessWithoutConfirmation": "409", "keepAwakeAcquiredAfterS": held, "keepAwakeReleasedAfterS": released,
            "failedBackup": "500 BACKUP_FAILED, nothing switched", "oldRevisionReadOnly": "409 REVISION_READ_ONLY",
            "switchBackExact": True, "historyKept": ops1, "repeatedSwitches": 4 + 2,
            "revisions": [f"{r['revisionNo']}:{r['status']}" for r in revisions(pid)]}


def s22_privacy_audio_keep_awake(clip: Path) -> dict[str, Any]:
    """Line audio limits and ownership, fingerprint protection and deletion, no private data anywhere, keep-awake
    released during review."""
    pid = STATE["pid"]
    ln = page(pid)["lines"][3]
    r = stack.http.get(f"/api/projects/{pid}/audio", params={"segmentId": ln["id"]})
    assert r.status_code == 200 and r.headers["content-type"].startswith("audio/"), (r.status_code, r.headers.get("content-type"))
    secs = wav_seconds(r.content)
    assert secs <= (ln["endMs"] - ln["startMs"]) / 1000 + 0.6 and secs <= 60.0, secs
    full = stack.http.get(f"/api/projects/{pid}/audio", params={"fromMs": 0, "toMs": 60_000})
    assert full.status_code == 200 and abs(wav_seconds(full.content) - 60.0) < 0.05
    assert code_of("GET", f"/api/projects/{pid}/audio", 400, params={"fromMs": 0, "toMs": 60_001}) == "AUDIO_RANGE_TOO_LONG"
    other = stack.create_project("E2E S22 other project")
    CREATED.append(other)
    call("GET", f"/api/projects/{other}/audio", 404, params={"segmentId": ln["id"]})
    call("GET", f"/api/projects/{pid}/audio", 404, params={"segmentId": str(uuid.uuid4())})

    embeddings = _assets_of(pid, "SPEAKER_EMBEDDING")
    assert embeddings, "the run produced no fingerprint assets to protect"
    assert {stack.http.get(f"/api/assets/{e}").status_code for e in embeddings} == {404}
    assert {stack.http.get(f"/api/assets/{e}/content").status_code for e in embeddings} == {404}
    priv = call("GET", f"/api/projects/{pid}/privacy", 200)
    assert priv["fingerprintFiles"] >= 1, priv
    call("POST", f"/api/projects/{pid}/voice-fingerprints/forget", 200)
    fp_dir = stack.DATA / "projects" / pid / "private" / "fingerprints"
    assert not list(fp_dir.glob("*.npy")) if fp_dir.exists() else True, "fingerprint files remain"
    assert call("GET", f"/api/projects/{pid}/privacy", 200)["fingerprintFiles"] == 0

    root_variants = {str(stack.DATA), str(stack.DATA).replace("\\", "/"), str(stack.DATA).replace("/", "\\")}
    leaks = [b[:120] for b in SEEN if any(x in b for x in root_variants) or any(e in b for e in embeddings)
             or re.search(r"fingerprints/|\.npy|SPEAKER_EMBEDDING", b)]
    assert not leaks, f"private data in responses: {leaks[:3]}"
    sample_keep_awake()
    assert REVIEW_KEEP_AWAKE and not any(REVIEW_KEEP_AWAKE), f"keep-awake held during review: {REVIEW_KEEP_AWAKE}"
    return {"lineAudioS": round(secs, 2), "rangeLimit": "60 s ok, 60.001 s -> 400 AUDIO_RANGE_TOO_LONG",
            "foreignProjectLine": "404", "fingerprintAssets": len(embeddings), "fingerprintsServed": False,
            "fingerprintsForgotten": True, "responsesScanned": len(SEEN), "keepAwakeSamplesDuringReview": len(REVIEW_KEEP_AWAKE),
            "keepAwakeHeldDuringReview": False}


SCENARIOS = {"S18": s18_import_and_process, "S19": s19_review_split_merge_undo, "S20": s20_glossary,
             "S21": s21_revisions, "S22": s22_privacy_audio_keep_awake}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--clip", required=True)
    ap.add_argument("--only", default=",".join(SCENARIOS))
    ap.add_argument("--keep-projects", action="store_true", help="leave the test projects open (e.g. for a UI check)")
    args = ap.parse_args()
    clip = Path(args.clip)
    selected = [s.strip() for s in args.only.split(",") if s.strip()]
    print(json.dumps({"api": stack.API, "web": stack.WEB, "storage": str(stack.DATA), "worker": stack.WORKER}), flush=True)
    stop_file = Path(__file__).with_name(".hold-awake.stop")
    stop_file.unlink(missing_ok=True)
    holder = subprocess.Popen(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                               str(Path(__file__).with_name("hold_awake.ps1")), "-StopFile", str(stop_file)])
    results: dict[str, Any] = {}
    try:
        for name in selected:
            began = time.monotonic()
            try:
                if name != "S18" and "pid" not in STATE:
                    raise RuntimeError("needs S18's project")
                detail = SCENARIOS[name](clip)
                results[name] = {"result": "PASS", "seconds": round(time.monotonic() - began), **detail}
            except Exception as e:  # noqa: BLE001
                results[name] = {"result": "FAIL", "seconds": round(time.monotonic() - began),
                                 "error": f"{type(e).__name__}: {e}", "trace": traceback.format_exc()[-1500:]}
            print(json.dumps({name: results[name]}, ensure_ascii=False), flush=True)
    finally:
        if stack.api_up() and not args.keep_projects:
            for pid in CREATED:
                stack.archive(pid)
        stop_file.touch()
        try:
            holder.wait(timeout=15)
        except subprocess.TimeoutExpired:
            holder.kill()
        stop_file.unlink(missing_ok=True)
    passed = sum(1 for r in results.values() if r["result"] == "PASS")
    print(f"SUMMARY {passed}/{len(results)} passed", flush=True)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
