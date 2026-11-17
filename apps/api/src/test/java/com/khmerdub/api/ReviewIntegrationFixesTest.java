package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.khmerdub.api.dialogue.DialogueRevision;
import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Regressions found by the M3b-2 Step 3 integration review. */
@IntegrationTest
class ReviewIntegrationFixesTest extends ReviewTestSupport {

    @Autowired JobRepository jobs;
    @Autowired TranscriptionService transcription;

    /**
     * Found by E2E S19 on real speech: a freshly imported revision stored the worker's diarization speaking time,
     * while every review change recounts speech time from the lines, so the first edit (even if undone) changed
     * every speaker's numbers. Imported numbers now use the same definition, and edit + undo restores everything.
     */
    @Test
    void importedSpeakerNumbersMatchTheReviewRecountSoEditPlusUndoChangesNothing() throws Exception {
        SpeechFixtures sx = new SpeechFixtures(fx, engine, jobs, projects, transcription, storage, json);
        UUID p = sx.audioReadyProject(12 * SpeechFixtures.MIN);
        assertThat(sx.run(sx.start(p, new StartTranscriptionRequest(null, null, null, false)), null, "-v1"))
                .isEqualTo(JobStatus.COMPLETED);
        UUID rev = revisions.findFirstByProjectIdAndStatus(p, DialogueRevision.Status.ACTIVE).orElseThrow().getId();
        // fixture lines: SPK_01 1000-2500 + 10000-12000, SPK_02 6000-8000 (the worker reported 3000 ms each)
        assertThat(jdbc.queryForList("SELECT speaker_code, speech_ms, segment_count FROM speakers WHERE revision_id = ? "
                + "ORDER BY speaker_code", rev)).containsExactly(
                Map.of("speaker_code", "SPK_01", "speech_ms", 3_500L, "segment_count", 2),
                Map.of("speaker_code", "SPK_02", "speech_ms", 2_000L, "segment_count", 1));
        Map<String, Object> imported = export(rev);
        UUID line = jdbc.queryForObject("SELECT id FROM dialogue_segments WHERE revision_id = ? AND sequence_no = 1",
                UUID.class, rev);
        Map<String, Object> edit = send(patch("/api/projects/{p}/segments/{s}", p, line),
                map("version", lineVersion(line), "editedText", "សួស្តី"), 200);
        send(post("/api/projects/{p}/review-operations/{o}/undo", p, operation(edit).get("id")), null, 200);
        assertThat(export(rev)).isEqualTo(imported);
    }

    private List<Map<String, Object>> history(UUID project, int limit) throws Exception {
        return sendList(get("/api/projects/{p}/review-operations", project).param("limit", String.valueOf(limit)), 200);
    }

    @Test
    void theChangeUndoWouldActOnIsListedEvenWhenOlderThanThePageAskedFor() throws Exception {
        Dialogue d = activeDialogue();
        List<String> ops = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID line = d.lines().get(i);
            ops.add((String) operation(send(patch("/api/projects/{p}/segments/{s}", d.project(), line),
                    map("version", lineVersion(line), "editedText", "km " + i), 200)).get("id"));
        }
        send(post("/api/projects/{p}/review-operations/{o}/undo", d.project(), ops.get(2)), null, 200);
        send(post("/api/projects/{p}/review-operations/{o}/undo", d.project(), ops.get(1)), null, 200);

        List<Map<String, Object>> page = history(d.project(), 2);   // the 2 newest are both undone
        assertThat(page).extracting(o -> o.get("id")).containsExactly(ops.get(2), ops.get(1), ops.get(0));
        assertThat(page).filteredOn(o -> Boolean.TRUE.equals(o.get("undoable"))).extracting(o -> o.get("id"))
                .containsExactly(ops.get(0));
        // a normal page is unchanged: newest first, exactly one undoable
        assertThat(history(d.project(), 15)).extracting(o -> o.get("id")).containsExactly(ops.get(2), ops.get(1), ops.get(0));
        send(post("/api/projects/{p}/review-operations/{o}/undo", d.project(), ops.get(0)), null, 200);
        assertThat(history(d.project(), 2)).noneMatch(o -> Boolean.TRUE.equals(o.get("undoable")));
    }

    @Test
    void aSpeakerCodeIsNeverReusedEvenAfterItsSpeakerWasUndone() throws Exception {
        Dialogue d = activeDialogue();                                   // SPK_01, SPK_02
        Map<String, Object> added = send(post("/api/projects/{p}/speakers", d.project()), map(), 200);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) added.get("speakers")).getFirst();
        assertThat(first.get("code")).isEqualTo("SPK_03");
        send(post("/api/projects/{p}/review-operations/{o}/undo", d.project(), operation(added).get("id")), null, 200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM speakers WHERE revision_id = ? AND speaker_code = 'SPK_03'",
                Long.class, d.revision())).isZero();
        Map<String, Object> again = send(post("/api/projects/{p}/speakers", d.project()), map(), 200);
        @SuppressWarnings("unchecked")
        Map<String, Object> second = ((List<Map<String, Object>>) again.get("speakers")).getFirst();
        assertThat(second.get("code")).isEqualTo("SPK_04");
    }

    @Test
    void aMalformedVoiceProfileIdIsABadRequestNotAServerError() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> err = send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", speakerVersion(d.spk1()), "voiceProfileId", "0123456789abcdef0123456789abcdef0123"), 400);
        assertThat(err).containsEntry("code", "INVALID_VOICE_PROFILE");
        assertThat(speakerRow(d.spk1()).get("voice_profile_id")).isNull();
        assertThat(operations(d.revision())).isZero();
    }
}
