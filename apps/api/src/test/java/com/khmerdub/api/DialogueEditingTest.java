package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.khmerdub.api.dialogue.DialogueRevision;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M3b-2 line editing: separate fields, active-revision only, conflicts, timing, split/merge, reassignment, filters. */
@IntegrationTest
class DialogueEditingTest extends ReviewTestSupport {

    @Test
    void chineseCorrectionAndKhmerGoToTheirOwnFieldsAndTheRecognisedTextNeverChanges() throws Exception {
        Dialogue d = activeDialogue();
        UUID l0 = d.lines().get(0);
        Map<String, Object> res = send(patch("/api/projects/{p}/segments/{s}", d.project(), l0),
                map("version", 0, "sourceTextEdited", "你好，我是", "editedText", "សួស្តី ខ្ញុំជា", "reviewStatus", "APPROVED"), 200);
        assertThat(operation(res)).containsEntry("type", "EDIT_SEGMENT").containsEntry("undoable", true);
        Map<String, Object> row = lineRow(l0);
        assertThat(row).containsEntry("source_text", "你好我是").containsEntry("source_text_edited", "你好，我是")
                .containsEntry("edited_text", "សួស្តី ខ្ញុំជា").containsEntry("review_status", "APPROVED");
        assertThat(row.get("translated_text")).isNull(); // the AI field is never written by the user

        // "" clears a field; a correction identical to the recognised text is no correction
        send(patch("/api/projects/{p}/segments/{s}", d.project(), l0),
                map("version", lineVersion(l0), "sourceTextEdited", "你好我是", "editedText", ""), 200);
        row = lineRow(l0);
        assertThat(row.get("source_text_edited")).isNull();
        assertThat(row.get("edited_text")).isNull();
        assertThat(row).containsEntry("source_text", "你好我是");
        // the API has no way to change the recognised text: an unknown field is simply ignored
        send(patch("/api/projects/{p}/segments/{s}", d.project(), l0),
                map("version", lineVersion(l0), "sourceText", "HACKED", "editedText", "x"), 200);
        assertThat(lineRow(l0)).containsEntry("source_text", "你好我是");
    }

    @Test
    void onlyTheActiveRevisionCanBeEditedAndOtherProjectsAnswer404() throws Exception {
        Dialogue d = activeDialogue();
        Dialogue ready = revision(d.project(), 2, DialogueRevision.Status.READY);
        Map<String, Object> err = send(patch("/api/projects/{p}/segments/{s}", d.project(), ready.lines().get(0)),
                map("version", 0, "editedText", "x"), 409);
        assertThat(err).containsEntry("code", "REVISION_READ_ONLY");
        assertThat(send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(ready.spk1(), 0), "sources", List.of(ref(ready.spk2(), 0))), 409))
                .containsEntry("code", "REVISION_READ_ONLY");

        Dialogue other = activeDialogue();
        send(patch("/api/projects/{p}/segments/{s}", d.project(), other.lines().get(0)),
                map("version", 0, "editedText", "x"), 404);
        assertThat(lineRow(other.lines().get(0)).get("edited_text")).isNull();
        assertThat(lineRow(ready.lines().get(0)).get("edited_text")).isNull();

        send(post("/api/projects/{id}/archive", d.project()), null, 200);
        assertThat(send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(0)),
                map("version", 0, "editedText", "x"), 409)).containsEntry("code", "PROJECT_ARCHIVED");
    }

    @Test
    void aStaleVersionIsRejectedInsteadOfOverwriting() throws Exception {
        Dialogue d = activeDialogue();
        UUID l1 = d.lines().get(1);
        send(patch("/api/projects/{p}/segments/{s}", d.project(), l1), map("version", 0, "editedText", "first"), 200);
        Map<String, Object> err = send(patch("/api/projects/{p}/segments/{s}", d.project(), l1),
                map("version", 0, "editedText", "second (based on an old version)"), 409);
        assertThat(err).containsEntry("code", "EDIT_CONFLICT");
        assertThat(lineRow(l1)).containsEntry("edited_text", "first");
        assertThat(operations(d.revision())).isEqualTo(1);
        // the version is required
        send(patch("/api/projects/{p}/segments/{s}", d.project(), l1), map("editedText", "x"), 400);
    }

    @Test
    void twoSimultaneousEditsOfOneLineGiveExactlyOneWinner() throws Exception {
        Dialogue d = activeDialogue();
        UUID l0 = d.lines().get(0);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Integer>> results = new java.util.ArrayList<>();
        for (String text : List.of("A", "B")) {
            results.add(pool.submit(() -> {
                start.await();
                return mvc.perform(patch("/api/projects/{p}/segments/{s}", d.project(), l0)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(map("version", 0, "editedText", text))))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new java.util.ArrayList<>();
        for (var f : results) {
            statuses.add(f.get(60, java.util.concurrent.TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(lineRow(l0).get("edited_text")).isIn("A", "B");
        assertThat(lineVersion(l0)).isEqualTo(1);
        assertThat(operations(d.revision())).isEqualTo(1);
    }

    @Test
    void aFailureHalfwayRollsBackWhatWasAlreadyWritten() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> before = export(d.revision());
        // the first line is valid and gets written, the second has a stale version: the whole change must vanish
        assertThat(send(post("/api/projects/{p}/segments/reassign", d.project()), map("speakerId", d.spk1(),
                "segments", List.of(ref(d.lines().get(1), 0), ref(d.lines().get(3), 99))), 409))
                .containsEntry("code", "EDIT_CONFLICT");
        assertThat(export(d.revision())).isEqualTo(before);
        assertThat(lineVersion(d.lines().get(1))).isZero();
        assertThat(operations(d.revision())).isZero();
    }

    @Test
    void timingIsValidatedAgainstNeighboursAndTheMovieLength() throws Exception {
        Dialogue d = activeDialogue();
        UUID l1 = d.lines().get(1); // 4000-6000, neighbours start at 1000 and 7000
        assertThat(send(patch("/api/projects/{p}/segments/{s}", d.project(), l1),
                map("version", 0, "startMs", 5_000, "endMs", 4_000), 400)).containsEntry("code", "INVALID_TIMING");
        assertThat(send(patch("/api/projects/{p}/segments/{s}", d.project(), l1),
                map("version", 0, "startMs", 7_500), 400)).containsEntry("code", "INVALID_TIMING"); // end before start
        assertThat(send(patch("/api/projects/{p}/segments/{s}", d.project(), l1),
                map("version", 0, "startMs", 7_500, "endMs", 8_000), 400)).containsEntry("code", "TIMING_ORDER");
        assertThat(send(patch("/api/projects/{p}/segments/{s}", d.project(), l1),
                map("version", 0, "startMs", 500), 400)).containsEntry("code", "TIMING_ORDER");
        assertThat(send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(3)),
                map("version", 0, "endMs", DURATION_MS + 1), 400)).containsEntry("code", "INVALID_TIMING");
        send(patch("/api/projects/{p}/segments/{s}", d.project(), l1), map("version", 0, "startMs", 3_900, "endMs", 6_200), 200);
        assertThat(lineRow(l1)).containsEntry("start_ms", 3_900L).containsEntry("end_ms", 6_200L);
        // speaker totals follow the new timing
        assertThat(speakerRow(d.spk2())).containsEntry("speech_ms", 2_300L + 2_000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void splitKeepsTheRecognisedTextAtWordBoundariesAndRenumbers() throws Exception {
        Dialogue d = activeDialogue();
        UUID l1 = d.lines().get(1); // 今天|去|哪里
        Map<String, Object> res = send(post("/api/projects/{p}/segments/{s}/split", d.project(), l1),
                map("version", 0, "atWord", 2), 200);
        assertThat(operation(res)).containsEntry("type", "SPLIT_SEGMENT");
        List<Map<String, Object>> lines = jdbc.queryForList("SELECT sequence_no, start_ms, end_ms, source_text, speaker_id "
                + "FROM dialogue_segments WHERE revision_id = ? ORDER BY sequence_no", d.revision());
        assertThat(lines).extracting(r -> r.get("source_text"))
                .containsExactly("你好我是", "今天去", "哪里", "回家吧", "碎碎在哪");
        assertThat(lines).extracting(r -> r.get("sequence_no")).containsExactly(0, 1, 2, 3, 4);
        assertThat(lines.get(1)).containsEntry("start_ms", 4_000L).containsEntry("end_ms", 5_000L);
        assertThat(lines.get(2)).containsEntry("start_ms", 5_000L).containsEntry("end_ms", 6_000L)
                .containsEntry("speaker_id", d.spk2());
        assertThat((List<Object>) res.get("lines")).hasSize(2);
        assertThat(speakerRow(d.spk2())).containsEntry("segment_count", 3);

        // a single-word part cannot be split again, out-of-range word indexes are refused
        UUID single = jdbc.queryForObject("SELECT id FROM dialogue_segments WHERE revision_id = ? AND sequence_no = 2",
                UUID.class, d.revision());
        assertThat(send(post("/api/projects/{p}/segments/{s}/split", d.project(), single),
                map("version", lineVersion(single), "atWord", 1), 409)).containsEntry("code", "SPLIT_NOT_POSSIBLE");
        assertThat(send(post("/api/projects/{p}/segments/{s}/split", d.project(), d.lines().get(0)),
                map("version", 0, "atWord", 2), 400)).containsEntry("code", "INVALID_SPLIT");
    }

    @Test
    void splittingALineWithYourOwnTextRequiresSayingWhereItGoes() throws Exception {
        Dialogue d = activeDialogue();
        UUID l0 = d.lines().get(0);
        send(patch("/api/projects/{p}/segments/{s}", d.project(), l0), map("version", 0, "editedText", "សួស្តី ខ្ញុំជា"), 200);
        assertThat(send(post("/api/projects/{p}/segments/{s}/split", d.project(), l0),
                map("version", 1, "atWord", 1), 409)).containsEntry("code", "SPLIT_NEEDS_TEXT");
        assertThat(lineRow(l0)).containsEntry("edited_text", "សួស្តី ខ្ញុំជា");
        send(post("/api/projects/{p}/segments/{s}/split", d.project(), l0), map("version", 1, "atWord", 1,
                "first", map("editedText", "សួស្តី"), "second", map("editedText", "ខ្ញុំជា")), 200);
        List<Map<String, Object>> parts = jdbc.queryForList("SELECT source_text, edited_text FROM dialogue_segments "
                + "WHERE revision_id = ? AND sequence_no IN (0, 1) ORDER BY sequence_no", d.revision());
        assertThat(parts.get(0)).containsEntry("source_text", "你好").containsEntry("edited_text", "សួស្តី");
        assertThat(parts.get(1)).containsEntry("source_text", "我是").containsEntry("edited_text", "ខ្ញុំជា");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mergeJoinsNeighboursAndNeedsASpeakerChoiceWhenTheyDiffer() throws Exception {
        Dialogue d = activeDialogue();
        UUID l0 = d.lines().get(0);
        UUID l1 = d.lines().get(1);
        UUID l2 = d.lines().get(2);
        assertThat(send(post("/api/projects/{p}/segments/merge", d.project()),
                map("first", ref(l0, 0), "second", ref(l2, 0)), 409)).containsEntry("code", "NOT_ADJACENT");
        assertThat(send(post("/api/projects/{p}/segments/merge", d.project()),
                map("first", ref(l0, 0), "second", ref(l1, 0)), 409)).containsEntry("code", "MERGE_SPEAKER_REQUIRED");
        send(patch("/api/projects/{p}/segments/{s}", d.project(), l1), map("version", 0, "sourceTextEdited", "今天去哪儿"), 200);
        Map<String, Object> res = send(post("/api/projects/{p}/segments/merge", d.project()),
                map("first", ref(l0, 0), "second", ref(l1, 1), "speakerId", d.spk1()), 200);
        assertThat(operation(res)).containsEntry("type", "MERGE_SEGMENTS");
        assertThat((List<Object>) res.get("removedLineIds")).containsExactly(l1.toString());
        Map<String, Object> merged = lineRow(l0);
        assertThat(merged).containsEntry("source_text", "你好我是今天去哪里")
                .containsEntry("source_text_edited", "你好我是今天去哪儿")
                .containsEntry("start_ms", 1_000L).containsEntry("end_ms", 6_000L)
                .containsEntry("speaker_id", d.spk1()).containsEntry("speaker_assignment", "MANUAL");
        assertThat(jdbc.queryForList("SELECT sequence_no FROM dialogue_segments WHERE revision_id = ? ORDER BY 1",
                Integer.class, d.revision())).containsExactly(0, 1, 2);
        assertThat(speakerRow(d.spk2())).containsEntry("segment_count", 1);
    }

    @Test
    void reassignMovesLinesAndMarksThemManual() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> res = send(post("/api/projects/{p}/segments/reassign", d.project()), map("speakerId", d.spk1(),
                "segments", List.of(ref(d.lines().get(1), 0), ref(d.lines().get(3), 0))), 200);
        assertThat(operation(res)).containsEntry("type", "REASSIGN_SEGMENT");
        assertThat(lineRow(d.lines().get(3))).containsEntry("speaker_id", d.spk1()).containsEntry("speaker_assignment", "MANUAL");
        assertThat(speakerRow(d.spk1())).containsEntry("segment_count", 4);
        assertThat(speakerRow(d.spk2())).containsEntry("segment_count", 0);
        assertThat(send(post("/api/projects/{p}/segments/reassign", d.project()), map("speakerId", d.spk1(),
                "segments", List.of(ref(d.lines().get(1), 0))), 409)).containsEntry("code", "EDIT_CONFLICT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listingCanBeFilteredAndCarriesVersionsAndWords() throws Exception {
        Dialogue d = activeDialogue();
        jdbc.update("UPDATE dialogue_segments SET flags = '{\"lowConfidence\": true}' WHERE id = ?", d.lines().get(2));
        send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(3)), map("version", 0, "editedText", "ស៊ុយស៊ុយ"), 200);
        Map<String, Object> all = send(get("/api/projects/{p}/dialogue", d.project()), null, 200);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) all.get("lines");
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).containsKeys("version", "words", "speakerAssignment");
        assertThat((List<Object>) send(get("/api/projects/{p}/dialogue", d.project()).param("speakerId", d.spk2().toString()),
                null, 200).get("lines")).hasSize(2);
        Map<String, Object> flagged = send(get("/api/projects/{p}/dialogue", d.project()).param("flag", "lowConfidence"), null, 200);
        assertThat(flagged).containsEntry("total", 1);
        assertThat((List<Object>) send(get("/api/projects/{p}/dialogue", d.project()).param("q", "ស៊ុយ"), null, 200)
                .get("lines")).hasSize(1);
        assertThat((List<Object>) send(get("/api/projects/{p}/dialogue", d.project()).param("q", "100%_"), null, 200)
                .get("lines")).isEmpty();
        send(get("/api/projects/{p}/dialogue", d.project()).param("flag", "bogus"), null, 400);
    }
}
