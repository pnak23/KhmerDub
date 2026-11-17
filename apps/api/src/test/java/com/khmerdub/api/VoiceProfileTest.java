package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Minimal local voice library: provider NONE + empty id, unique names, conflict detection, deactivate not delete. */
@IntegrationTest
class VoiceProfileTest extends ReviewTestSupport {

    @Test
    void createUpdateAndDeactivate() throws Exception {
        Map<String, Object> v = send(post("/api/voice-profiles"),
                map("displayName", "Warm male", "voiceCategory", "ADULT_MALE", "style", "calm"), 201);
        assertThat(v).containsEntry("provider", "NONE").containsEntry("providerVoiceId", "")
                .containsEntry("language", "km-KH").containsEntry("active", true).containsEntry("usedBySpeakers", 0);
        String id = (String) v.get("id");
        // names are unique, ignoring case
        assertThat(send(post("/api/voice-profiles"), map("displayName", "WARM MALE", "voiceCategory", "ADULT_MALE"), 409))
                .containsEntry("code", "NAME_EXISTS");
        send(post("/api/voice-profiles"), map("displayName", "x", "voiceCategory", "ROBOT"), 400);

        Map<String, Object> updated = send(patch("/api/voice-profiles/{id}", id),
                map("expectedUpdatedAt", v.get("updatedAt"), "displayName", "Warm male 2", "style", ""), 200);
        assertThat(updated).containsEntry("displayName", "Warm male 2").containsEntry("provider", "NONE");
        assertThat(updated.get("style")).isNull();
        // based on an old state: refused, nothing changed
        assertThat(send(patch("/api/voice-profiles/{id}", id),
                map("expectedUpdatedAt", v.get("updatedAt"), "displayName", "Stale"), 409)).containsEntry("code", "EDIT_CONFLICT");
        send(patch("/api/voice-profiles/{id}", UUID.randomUUID()), map("expectedUpdatedAt", v.get("updatedAt"),
                "displayName", "Nobody"), 404);

        // the provider fields cannot be set through the API (unknown fields are ignored)
        Map<String, Object> sneaky = send(patch("/api/voice-profiles/{id}", id), map("expectedUpdatedAt",
                updated.get("updatedAt"), "voiceCategory", "CUSTOM", "provider", "gemini", "providerVoiceId", "x"), 200);
        assertThat(sneaky).containsEntry("provider", "NONE").containsEntry("providerVoiceId", "");

        Map<String, Object> off = send(delete("/api/voice-profiles/{id}", id)
                .param("expectedUpdatedAt", (String) sneaky.get("updatedAt")), null, 200);
        assertThat(off).containsEntry("active", false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voice_profiles WHERE id = ?::uuid", Long.class, id)).isEqualTo(1);
        assertThat(sendList(get("/api/voice-profiles"), 200)).isEmpty();
        List<Map<String, Object>> all = sendList(get("/api/voice-profiles").param("includeInactive", "true"), 200);
        assertThat(all).hasSize(1);
    }

    @Test
    void usageIsCounted() throws Exception {
        Dialogue d = activeDialogue();
        Map<String, Object> v = send(post("/api/voice-profiles"), map("displayName", "Girl", "voiceCategory", "GIRL"), 201);
        send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk2()), map("version", 0, "voiceProfileId", v.get("id")), 200);
        assertThat(send(get("/api/voice-profiles/{id}", v.get("id")), null, 200)).containsEntry("usedBySpeakers", 1);
    }
}
