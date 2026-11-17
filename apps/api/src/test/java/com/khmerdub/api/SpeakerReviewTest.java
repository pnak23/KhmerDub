package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** M3b-2 speaker review: create, rename/update, voice, merge (ids kept), split, reassignment rules, conflicts. */
@IntegrationTest
class SpeakerReviewTest extends ReviewTestSupport {

    @Test
    @SuppressWarnings("unchecked")
    void createRenameAndReviewSpeakers() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> created = send(post("/api/projects/{p}/speakers", d.project()),
                map("characterName", "Grandpa", "suggestedCategory", "ELDERLY_MALE"), 200);
        assertThat(operation(created)).containsEntry("type", "CREATE_SPEAKER");
        Map<String, Object> view = ((List<Map<String, Object>>) created.get("speakers")).getFirst();
        assertThat(view).containsEntry("code", "SPK_03").containsEntry("characterName", "Grandpa")
                .containsEntry("origin", "MANUAL").containsEntry("segmentCount", 0);

        Map<String, Object> renamed = send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", 0, "characterName", "Pei"), 200);
        assertThat(operation(renamed)).containsEntry("type", "RENAME_SPEAKER");
        Map<String, Object> reviewed = send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", 1, "reviewStatus", "REVIEWED", "description", "the mother", "suggestedCategory", ""), 200);
        assertThat(operation(reviewed)).containsEntry("type", "UPDATE_SPEAKER");
        assertThat(speakerRow(d.spk1())).containsEntry("character_name", "Pei").containsEntry("review_status", "REVIEWED")
                .containsEntry("description", "the mother");
        // stale version
        assertThat(send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", 1, "characterName", "Mei"), 409)).containsEntry("code", "EDIT_CONFLICT");
        assertThat(speakerRow(d.spk1())).containsEntry("character_name", "Pei");
        // the speakers listing carries the review fields
        List<Map<String, Object>> list = sendList(get("/api/projects/{p}/speakers", d.project()), 200);
        assertThat(list).hasSize(3);
        assertThat(list.getFirst()).containsKeys("version", "origin", "mergedIntoId", "voiceAssignment", "sampleAssetIds");
    }

    @Test
    void voiceAssignmentUsesTheLocalLibraryOnly() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> voice = send(post("/api/voice-profiles"), map("displayName", "Warm male", "voiceCategory", "ADULT_MALE"), 201);
        String voiceId = (String) voice.get("id");
        Map<String, Object> res = send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", 0, "voiceProfileId", voiceId), 200);
        assertThat(operation(res)).containsEntry("type", "ASSIGN_VOICE");
        assertThat(speakerRow(d.spk1())).containsEntry("voice_profile_id", UUID.fromString(voiceId))
                .containsEntry("voice_assignment", "MANUAL");
        send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk2()),
                map("version", 0, "voiceProfileId", UUID.randomUUID().toString()), 404);
        // a deactivated profile cannot be newly assigned
        send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/voice-profiles/{id}", voiceId)
                .param("expectedUpdatedAt", (String) voice.get("updatedAt")), null, 200);
        assertThat(send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk2()),
                map("version", 0, "voiceProfileId", voiceId), 409)).containsEntry("code", "VOICE_INACTIVE");
        // "" clears
        send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()), map("version", 1, "voiceProfileId", ""), 200);
        assertThat(speakerRow(d.spk1())).containsEntry("voice_assignment", "NONE");
        assertThat(speakerRow(d.spk1()).get("voice_profile_id")).isNull();
    }

    @Test
    void mergeKeepsTheMergedSpeakerAndMovesAllItsLines() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> res = send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(d.spk1(), 0), "sources", List.of(ref(d.spk2(), 0))), 200);
        assertThat(operation(res)).containsEntry("type", "MERGE_SPEAKERS");
        Map<String, Object> merged = speakerRow(d.spk2());
        assertThat(merged).containsEntry("merged_into_id", d.spk1()).containsEntry("segment_count", 0); // kept, id intact
        assertThat(speakerRow(d.spk1())).containsEntry("segment_count", 4).containsEntry("speech_ms", 2_000L + 2_000 + 2_000 + 2_000);
        for (UUID line : d.lines()) {
            assertThat(lineRow(line)).containsEntry("speaker_id", d.spk1());
        }
        // the merge recorded every moved line (2 lines) plus the merged speaker
        String payload = jdbc.queryForObject("SELECT payload::text FROM review_operations WHERE revision_id = ?",
                String.class, d.revision());
        assertThat(payload).contains(d.lines().get(1).toString(), d.lines().get(3).toString(), d.spk2().toString());
        // a merged speaker can no longer receive lines or be edited
        assertThat(send(post("/api/projects/{p}/segments/reassign", d.project()), map("speakerId", d.spk2(),
                "segments", List.of(ref(d.lines().get(0), 1))), 409)).containsEntry("code", "SPEAKER_MERGED");
        assertThat(send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk2()),
                map("version", speakerVersion(d.spk2()), "characterName", "x"), 409)).containsEntry("code", "SPEAKER_MERGED");
    }

    @Test
    void anInvalidPartRollsBackTheWholeMerge() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> before = export(d.revision());
        // one source is from another project: nothing may change
        Dialogue other = activeDialogue();
        send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(d.spk1(), 0), "sources", List.of(ref(d.spk2(), 0), ref(other.spk1(), 0))), 404);
        // one source has a stale version: nothing may change either
        send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(d.spk1(), 0), "sources", List.of(ref(d.spk2(), 7))), 409);
        assertThat(export(d.revision())).isEqualTo(before);
        assertThat(operations(d.revision())).isZero();
        send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(d.spk1(), 0), "sources", List.of(ref(d.spk1(), 0))), 400);
    }

    @Test
    @SuppressWarnings("unchecked")
    void splitMovesChosenLinesToANewSpeaker() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> res = send(post("/api/projects/{p}/speakers/{s}/split", d.project(), d.spk2()),
                map("version", 0, "segments", List.of(ref(d.lines().get(3), 0)), "characterName", "Sui Sui"), 200);
        assertThat(operation(res)).containsEntry("type", "SPLIT_SPEAKER");
        Map<String, Object> created = jdbc.queryForMap("SELECT * FROM speakers WHERE revision_id = ? AND origin = 'SPLIT'",
                d.revision());
        assertThat(created).containsEntry("character_name", "Sui Sui").containsEntry("speaker_code", "SPK_03")
                .containsEntry("segment_count", 1);
        assertThat(lineRow(d.lines().get(3))).containsEntry("speaker_id", created.get("id"));
        assertThat(speakerRow(d.spk2())).containsEntry("segment_count", 1);
        assertThat((List<Object>) res.get("speakers")).hasSize(2);
        // lines of another speaker cannot be split off this one
        assertThat(send(post("/api/projects/{p}/speakers/{s}/split", d.project(), d.spk2()),
                map("version", speakerVersion(d.spk2()), "segments", List.of(ref(d.lines().get(0), 0))), 409))
                .containsEntry("code", "LINE_NOT_OF_SPEAKER");
    }
}
