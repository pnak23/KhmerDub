package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.khmerdub.api.dialogue.DialogueRevision;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Undo is exact, last-change-first, and refused when anything it would restore changed since. */
@IntegrationTest
class ReviewUndoTest extends ReviewTestSupport {

    private String undo(Dialogue d, Map<String, Object> result, int expectedStatus) throws Exception {
        String opId = (String) operation(result).get("id");
        Map<String, Object> res = send(post("/api/projects/{p}/review-operations/{o}/undo", d.project(), opId), null,
                expectedStatus);
        return (String) res.get("code");
    }

    @Test
    void everyKindOfChangeIsUndoneExactly() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> original = export(d.revision());

        Map<String, Object> s1 = send(post("/api/projects/{p}/segments/{s}/split", d.project(), d.lines().get(1)),
                map("version", 0, "atWord", 1), 200);
        Map<String, Object> afterSplit = export(d.revision());
        UUID l1 = d.lines().get(1);
        UUID l2 = d.lines().get(2);
        Map<String, Object> m1 = send(post("/api/projects/{p}/segments/merge", d.project()),
                map("first", ref(l1, lineVersion(l1)), "second",
                        ref(jdbc.queryForObject("SELECT id FROM dialogue_segments WHERE revision_id = ? AND sequence_no = 2",
                                UUID.class, d.revision()), 0)), 200);
        Map<String, Object> e1 = send(patch("/api/projects/{p}/segments/{s}", d.project(), l2),
                map("version", 0, "sourceTextEdited", "回家吧！", "editedText", "ទៅផ្ទះ", "reviewStatus", "APPROVED"), 200);
        Map<String, Object> ms = send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(d.spk1(), speakerVersion(d.spk1())), "sources",
                        List.of(ref(d.spk2(), speakerVersion(d.spk2())))), 200);
        Map<String, Object> sp = send(post("/api/projects/{p}/speakers/{s}/split", d.project(), d.spk1()),
                map("version", speakerVersion(d.spk1()), "segments", List.of(ref(d.lines().get(3), lineVersion(d.lines().get(3))))),
                200);
        Map<String, Object> cr = send(post("/api/projects/{p}/speakers", d.project()), map("characterName", "Extra"), 200);
        Map<String, Object> rn = send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", speakerVersion(d.spk1()), "characterName", "Pei"), 200);
        assertThat(operations(d.revision())).isEqualTo(7);

        // last-first only
        assertThat(undo(d, s1, 409)).isEqualTo("UNDO_ORDER");
        for (Map<String, Object> op : List.of(rn, cr, sp, ms, e1, m1)) {
            assertThat(undo(d, op, 200)).isNull();
        }
        assertThat(export(d.revision())).isEqualTo(afterSplit);
        assertThat(undo(d, s1, 200)).isNull();
        assertThat(export(d.revision())).isEqualTo(original);
        // undone twice is refused, and nothing is left undoable
        assertThat(undo(d, s1, 409)).isEqualTo("ALREADY_UNDONE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_operations WHERE revision_id = ? AND undone_at IS NULL",
                Long.class, d.revision())).isZero();
    }

    @Test
    void undoIsRefusedWhenARecordChangedSinceAndChangesNothing() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> merge = send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(d.spk1(), 0), "sources", List.of(ref(d.spk2(), 0))), 200);
        // something outside the review log changes a moved line (e.g. a later translation step)
        jdbc.update("UPDATE dialogue_segments SET translated_text = 'ai', version = version + 1 WHERE id = ?", d.lines().get(1));
        Map<String, Object> before = export(d.revision());
        assertThat(undo(d, merge, 409)).isEqualTo("UNDO_CONFLICT");
        assertThat(export(d.revision())).isEqualTo(before);
        assertThat(speakerRow(d.spk2())).containsEntry("merged_into_id", d.spk1());
    }

    @Test
    void undoOnlyWorksOnTheActiveRevision() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> edit = send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(0)),
                map("version", 0, "editedText", "សួស្តី"), 200);
        // a new revision becomes active; the edited one is superseded (read-only) and keeps its edit
        jdbc.update("UPDATE dialogue_revisions SET status = 'SUPERSEDED' WHERE id = ?", d.revision());
        Dialogue next = revision(d.project(), 2, DialogueRevision.Status.ACTIVE);
        assertThat(undo(d, edit, 409)).isEqualTo("REVISION_READ_ONLY");
        assertThat(lineRow(d.lines().get(0))).containsEntry("edited_text", "សួស្តី");
        // the history endpoint shows it, but not as undoable
        List<Map<String, Object>> ops = sendList(MockMvcRequestBuilders.get("/api/projects/{p}/review-operations", d.project())
                .param("revisionId", d.revision().toString()), 200);
        assertThat(ops).hasSize(1);
        assertThat(ops.getFirst()).containsEntry("undoable", false).containsEntry("type", "EDIT_SEGMENT");
        assertThat(sendList(MockMvcRequestBuilders.get("/api/projects/{p}/review-operations", next.project()), 200)).isEmpty();
    }

    // ------------------------------------------------------------------------------------ ABA / monotonic versions

    private Map<String, Object> editKhmer(Dialogue d, UUID line, long version, String text, int expected) throws Exception {
        return send(patch("/api/projects/{p}/segments/{s}", d.project(), line), map("version", version, "editedText", text),
                expected);
    }

    @SuppressWarnings("unchecked")
    private long versionIn(Map<String, Object> result, UUID line) {
        return ((List<Map<String, Object>>) result.get("lines")).stream().filter(l -> line.toString().equals(l.get("id")))
                .map(l -> ((Number) l.get("version")).longValue()).findFirst().orElseThrow();
    }

    /** The exact sequence: read v1, change to v2, undo, then the old request based on v1 must be refused. */
    @Test
    void aStaleRequestIsRefusedAfterAnUndoRestoredTheContentItWasBasedOn() throws Exception {
        Dialogue d = activeDialogue();
        UUID line = d.lines().get(0);
        editKhmer(d, line, 0, "A", 200);
        long clientA = lineVersion(line);                       // 1. client A reads version 1 (content "A")
        assertThat(clientA).isEqualTo(1);
        Map<String, Object> change = editKhmer(d, line, clientA, "B", 200); // 2. a change makes it version 2
        assertThat(lineVersion(line)).isEqualTo(2);
        Map<String, Object> undone = send(post("/api/projects/{p}/review-operations/{o}/undo", d.project(),
                operation(change).get("id")), null, 200);       // 3. undo restores content "A" ...
        assertThat(lineRow(line)).containsEntry("edited_text", "A");
        assertThat(lineVersion(line)).isEqualTo(3);             //    ... with a NEW version, never 1 again
        assertThat(versionIn(undone, line)).isEqualTo(3);
        Map<String, Object> err = editKhmer(d, line, clientA, "stale", 409); // 4. client A's old request
        assertThat(err).containsEntry("code", "EDIT_CONFLICT");
        assertThat(editKhmer(d, line, 2, "stale", 409)).containsEntry("code", "EDIT_CONFLICT");
        assertThat(lineRow(line)).containsEntry("edited_text", "A");
        assertThat(lineVersion(line)).isEqualTo(3);
        // a request based on the current version works
        assertThat(versionIn(editKhmer(d, line, 3, "C", 200), line)).isEqualTo(4);
    }

    @Test
    void multiStepUndoOnTheSameRowKeepsVersionsStrictlyIncreasing() throws Exception {
        Dialogue d = activeDialogue();
        UUID line = d.lines().get(1);
        List<Long> seen = new java.util.ArrayList<>(List.of(lineVersion(line)));
        Map<String, Object> a = editKhmer(d, line, 0, "A", 200);
        seen.add(lineVersion(line));
        Map<String, Object> b = editKhmer(d, line, 1, "B", 200);
        seen.add(lineVersion(line));
        Map<String, Object> c = editKhmer(d, line, 2, "C", 200);
        seen.add(lineVersion(line));
        // still last-first
        assertThat(undo(d, a, 409)).isEqualTo("UNDO_ORDER");
        String[] expected = {"B", "A", null};
        List<Map<String, Object>> ops = List.of(c, b, a);
        for (int i = 0; i < ops.size(); i++) {
            assertThat(undo(d, ops.get(i), 200)).isNull();
            assertThat(lineRow(line).get("edited_text")).isEqualTo(expected[i]);
            seen.add(lineVersion(line));
        }
        assertThat(seen).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L); // never reused, never decreasing
        // every version issued before the last undo is refused now
        for (long old = 0; old < 6; old++) {
            assertThat(editKhmer(d, line, old, "stale " + old, 409)).containsEntry("code", "EDIT_CONFLICT");
        }
        assertThat(lineRow(line).get("edited_text")).isNull();
        assertThat(editKhmer(d, line, 6, "fresh", 200)).isNotNull();
    }

    @Test
    void structuralChangesOnTheSameRowUndoExactlyWithMonotonicVersions() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> original = export(d.revision());
        UUID line = d.lines().get(1);
        Map<String, Object> split = send(post("/api/projects/{p}/segments/{s}/split", d.project(), line),
                map("version", 0, "atWord", 1), 200);                          // line: v0 -> v1, new part v0
        UUID part = jdbc.queryForObject("SELECT id FROM dialogue_segments WHERE revision_id = ? AND sequence_no = 2",
                UUID.class, d.revision());
        Map<String, Object> e1 = editKhmer(d, line, 1, "first", 200);         // line v2
        Map<String, Object> e2 = editKhmer(d, part, 0, "second", 200);        // part v1
        assertThat(undo(d, e2, 200)).isNull();                                // part v2 (= content of v0)
        assertThat(undo(d, e1, 200)).isNull();                                // line v3 (= content of v1)
        assertThat(undo(d, split, 200)).isNull();                             // line v4 (= content of v0), part deleted
        assertThat(export(d.revision())).isEqualTo(original);
        assertThat(lineVersion(line)).isEqualTo(4);
        for (long old = 0; old < 4; old++) {
            assertThat(editKhmer(d, line, old, "stale", 409)).containsEntry("code", "EDIT_CONFLICT");
        }
        assertThat(export(d.revision())).isEqualTo(original);
    }

    @Test
    void aRowReinsertedByUndoGetsANewVersion() throws Exception {
        Dialogue d = activeDialogue();
        UUID first = d.lines().get(2);
        UUID second = d.lines().get(3);
        jdbc.update("UPDATE dialogue_segments SET speaker_id = ? WHERE id = ?", d.spk1(), second);
        Map<String, Object> merge = send(post("/api/projects/{p}/segments/merge", d.project()),
                map("first", ref(first, 0), "second", ref(second, 0)), 200); // "second" deleted at version 0
        assertThat(undo(d, merge, 200)).isNull();
        assertThat(lineVersion(second)).isEqualTo(1);                       // re-inserted with a never-issued version
        assertThat(editKhmer(d, second, 0, "stale", 409)).containsEntry("code", "EDIT_CONFLICT");
        assertThat(versionIn(editKhmer(d, second, 1, "ok", 200), second)).isEqualTo(2);
    }

    @Test
    void speakerVersionsAreMonotonicAcrossUndoToo() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> rename = send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", 0, "characterName", "Pei"), 200);
        assertThat(undo(d, rename, 200)).isNull();
        assertThat(speakerVersion(d.spk1())).isEqualTo(2);
        assertThat(speakerRow(d.spk1()).get("character_name")).isNull();
        for (long old = 0; old < 2; old++) {
            assertThat(send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                    map("version", old, "characterName", "stale"), 409)).containsEntry("code", "EDIT_CONFLICT");
        }
    }

    @Test
    void anOutsideChangeBetweenTwoUndosIsStillDetected() throws Exception {
        Dialogue d = activeDialogue();
        UUID line = d.lines().get(0);
        Map<String, Object> a = editKhmer(d, line, 0, "A", 200);  // v1
        Map<String, Object> b = editKhmer(d, line, 1, "B", 200);  // v2
        assertThat(undo(d, b, 200)).isNull();                    // v3, same content as v1
        // something outside the review log changes the row after the undo (v4)
        jdbc.update("UPDATE dialogue_segments SET translated_text = 'ai', version = version + 1 WHERE id = ?", line);
        Map<String, Object> before = export(d.revision());
        assertThat(undo(d, a, 409)).isEqualTo("UNDO_CONFLICT");
        assertThat(export(d.revision())).isEqualTo(before);
        assertThat(lineVersion(line)).isEqualTo(4);
    }

    @Test
    void historyMarksOnlyTheNewestChangeUndoable() throws Exception {
        Dialogue d = activeDialogue();
        send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(0)), map("version", 0, "editedText", "a"), 200);
        send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(1)), map("version", 0, "editedText", "b"), 200);
        List<Map<String, Object>> ops = sendList(MockMvcRequestBuilders.get("/api/projects/{p}/review-operations",
                d.project()), 200);
        assertThat(ops).extracting(o -> o.get("undoable")).containsExactly(true, false);
        assertThat((String) ops.getFirst().get("summary")).contains("Line 2");
    }
}
