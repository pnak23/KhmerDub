package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regressions found by the M3b-2 Step 3 integration review. */
@IntegrationTest
class ReviewIntegrationFixesTest extends ReviewTestSupport {

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
