package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Privacy of the M3b-2 backend: no fingerprint (embedding) ever leaves through the new endpoints or lands in the
 * review log, and the System ML status copies out only allow-listed fields (no paths, no secrets).
 */
@IntegrationTest
class ReviewPrivacyTest extends ReviewTestSupport {

    static final Pattern LEAK = Pattern.compile("\\.npy|private/fingerprints|speaker_embedding|SPEAKER_EMBEDDING|"
            + "\"embeddings?\"\\s*:|\\[\\s*-?\\d\\.\\d{3,}(\\s*,\\s*-?\\d\\.\\d{3,}){15,}", Pattern.CASE_INSENSITIVE);

    @AfterEach
    void removeStatusFiles() throws Exception {
        Files.deleteIfExists(storage.resolve("system/worker-status.json"));
        Files.deleteIfExists(storage.resolve("models/manifest.verified.json"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void noFingerprintLeavesThroughReviewEndpointsOrTheReviewLog() throws Exception {
        Dialogue d = activeDialogue();
        // a fingerprint file + asset exist for this project
        String rel = StorageService.privateDir(d.project(), "fingerprints") + "/speakers-x.npy";
        Path f = storage.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, new byte[] {1, 2, 3, 4});
        var emb = assets.save(new MediaAsset(d.project(), MediaAssetType.SPEAKER_EMBEDDING, "speakers-x.npy", rel,
                "application/octet-stream", 4, StorageService.sha256(f), null));

        StringBuilder all = new StringBuilder();
        all.append(json.writeValueAsString(send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(0)),
                map("version", 0, "editedText", "សួស្តី"), 200)));
        all.append(json.writeValueAsString(send(post("/api/projects/{p}/speakers/merge", d.project()),
                map("target", ref(d.spk1(), 0), "sources", List.of(ref(d.spk2(), 0))), 200)));
        all.append(json.writeValueAsString(send(post("/api/projects/{p}/glossary", d.project()), map("sourceTerm", "岁岁",
                "variants", List.of("碎碎")), 201)));
        all.append(json.writeValueAsString(send(post("/api/projects/{p}/glossary/preview", d.project()), map(), 200)));
        for (String url : List.of("/api/projects/{p}/dialogue", "/api/projects/{p}/speakers",
                "/api/projects/{p}/review-operations", "/api/projects/{p}/glossary", "/api/voice-profiles",
                "/api/system/ml")) {
            all.append(raw(url, d.project()));
        }
        all.append(raw("/api/projects/{p}/dialogue-revisions/{r}/edit-summary", d.project(), d.revision()));
        assertThat(LEAK.matcher(all).find()).as("fingerprint data in a response").isFalse();
        assertThat(all.toString()).doesNotContain(emb.getId().toString());

        String log = String.join("\n", jdbc.queryForList("SELECT payload::text FROM review_operations", String.class));
        assertThat(LEAK.matcher(log).find()).as("fingerprint data in the review log").isFalse();
        // the embedding asset itself stays unreachable
        assertThat(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/assets/{id}/content", emb.getId())).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mlStatusCopiesOnlyAllowListedFields() throws Exception {
        Map<String, Object> empty = send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/system/ml"), null, 200);
        assertThat((Map<String, Object>) empty.get("models")).containsEntry("manifest", "missing");
        assertThat((Map<String, Object>) empty.get("worker")).containsEntry("reported", false);

        Path manifest = storage.resolve("models/manifest.verified.json");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, """
                {"models": {"pyannote/speaker-diarization-community-1": {"revision": "3533c8cf8e369892e6b79ff1bf80f7b0286a54ee",
                  "dir": "../../secret", "files": 8, "bytes": 32832557, "license": "cc-by-4.0", "verifiedAt": "2026-11-13T13:30:21",
                  "token": "hf_abcdefghijklmnopqrstuvwxyz"},
                  "C:\\\\evil": {"revision": "x"}}}""");
        Path status = storage.resolve("system/worker-status.json");
        Files.createDirectories(status.getParent());
        Files.writeString(status, """
                {"reportedAt": "2026-11-16T07:00:00+00:00", "platform": "Linux x86_64", "token": "secret-token-value",
                 "ml": {"ok": true, "versionsOk": true, "sileroVadOk": true, "modelsRoot": "/data/models",
                        "models": {"pyannote/speaker-diarization-community-1": true, "/etc/passwd": true}},
                 "offline": {"HF_HUB_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1", "PYANNOTE_METRICS_ENABLED": "0",
                             "HF_TOKEN": "hf_abcdefghijklmnopqrstuvwxyz"},
                 "network": {"internetBlocked": true, "redis": "ok", "spring": "D:\\\\KhmerDub\\\\data",
                             "isolatedAndConnected": true}}""");
        String body = raw("/api/system/ml");
        assertThat(body).doesNotContain("hf_", "secret", "token", "/data", "..", "evil", "passwd", "KhmerDub",
                "modelsRoot", storage.root().toString().replace("\\", "\\\\"));
        Map<String, Object> st = json.readValue(body, Map.class);
        Map<String, Object> worker = (Map<String, Object>) st.get("worker");
        assertThat(worker).containsEntry("reported", true).containsEntry("platform", "Linux x86_64");
        assertThat((Map<String, Object>) worker.get("network")).containsEntry("isolatedAndConnected", true)
                .containsEntry("redis", "ok").containsEntry("spring", null);
        assertThat((Map<String, Object>) worker.get("offline")).containsEntry("HF_HUB_OFFLINE", "1").doesNotContainKey("HF_TOKEN");
        Map<String, Object> models = (Map<String, Object>) ((Map<String, Object>) st.get("models")).get("list");
        assertThat(models).containsOnlyKeys("pyannote/speaker-diarization-community-1");
        assertThat((Map<String, Object>) models.get("pyannote/speaker-diarization-community-1"))
                .containsEntry("files", 8).containsEntry("filesPresent", null);
    }
}
