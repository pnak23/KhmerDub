package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEngine.DispatchOrder;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.privacy.PrivacyAction;
import com.khmerdub.api.privacy.PrivacyService;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Forgetting voice fingerprints / deleting voice samples removes the files physically and verifies it. */
@IntegrationTest
class PrivacyTest {

    static final long MIN = 60_000;

    @Autowired JobEngine engine;
    @Autowired JobRepository jobs;
    @Autowired MediaAssetRepository assets;
    @Autowired ProjectService projects;
    @Autowired TranscriptionService transcription;
    @Autowired PrivacyService privacy;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    EngineFixtures fx;
    SpeechFixtures sx;

    @BeforeEach
    void setUp() {
        fx = new EngineFixtures(projects, assets, storage, engine, jdbc);
        fx.reset();
        sx = new SpeechFixtures(fx, engine, jobs, projects, transcription, storage, json);
    }

    private UUID transcribed() throws Exception {
        UUID p = sx.audioReadyProject(12 * MIN);
        assertThat(sx.run(sx.start(p, new StartTranscriptionRequest(null, null, null, false)), null, ""))
                .isEqualTo(JobStatus.COMPLETED);
        return p;
    }

    private List<Path> files(UUID p, MediaAssetType type) {
        return assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p, type).stream()
                .map(a -> storage.resolve(a.getStoragePath())).toList();
    }

    @Test
    void forgetFingerprintsDeletesFilesPhysicallyButKeepsVoiceSamples() throws Exception {
        UUID p = transcribed();
        List<Path> embeddings = files(p, MediaAssetType.SPEAKER_EMBEDDING);
        List<Path> samples = files(p, MediaAssetType.SPEAKER_SAMPLE);
        assertThat(embeddings).isNotEmpty().allMatch(Files::exists);
        // A leftover from a failed attempt (e.g. a late write) is found and removed too.
        Path leftover = storage.resolve(StorageService.projectDir(p) + "/temp/attempts/" + UUID.randomUUID()
                + "/private/fingerprints/late.npy");
        Files.createDirectories(leftover.getParent());
        Files.write(leftover, new byte[] {1});

        PrivacyAction action = privacy.forgetFingerprints(p);

        assertThat(action.isVerified()).isTrue();
        assertThat(action.getFilesDeleted()).isEqualTo(embeddings.size() + 1);
        assertThat(embeddings).noneMatch(Files::exists);
        assertThat(Files.exists(leftover)).isFalse();
        assertThat(Files.exists(storage.resolve(StorageService.privateDir(p, "fingerprints")))).isFalse();
        assertThat(assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p, MediaAssetType.SPEAKER_EMBEDDING)).isEmpty();
        assertThat(samples).allMatch(Files::exists);                       // separate data, separate action
        assertThat(privacy.summary(p)).containsEntry("fingerprintFiles", 0L).containsEntry("voiceSampleFiles", 2L);

        PrivacyAction samplesAction = privacy.deleteVoiceSamples(p);
        assertThat(samplesAction.isVerified()).isTrue();
        assertThat(samplesAction.getFilesDeleted()).isEqualTo(2);
        assertThat(samples).noneMatch(Files::exists);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM privacy_actions WHERE project_id = ? AND verified", Long.class, p))
                .isEqualTo(2L);
        // The dialogue (names, lines) is unaffected.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dialogue_segments WHERE project_id = ?", Long.class, p)).isEqualTo(3L);
    }

    @Test
    void refusedWhileSpeakersAreBeingProcessed() throws Exception {
        UUID p = sx.audioReadyProject(12 * MIN);
        sx.start(p, new StartTranscriptionRequest(null, null, null, false));
        assertThatThrownBy(() -> privacy.forgetFingerprints(p)).isInstanceOf(ApiException.class)
                .hasMessageContaining("being processed");
    }

    @Test
    void sweepRemovesPrivateLeftoversOfEndedAttemptsOnly() throws Exception {
        UUID p = sx.audioReadyProject(12 * MIN);
        sx.start(p, new StartTranscriptionRequest(null, null, null, false));
        engine.admitQueued();
        DispatchOrder live = engine.reserveDispatches("cpu", 1).getFirst();
        engine.claim(live.attemptId(), "w");
        Path liveFile = storage.resolve(StorageService.attemptWorkDir(p, live.attemptId()) + "/private/fingerprints/x.npy");
        Path endedFile = storage.resolve(StorageService.attemptWorkDir(p, UUID.randomUUID()) + "/private/voice-samples/y.wav");
        for (Path f : List.of(liveFile, endedFile)) {
            Files.createDirectories(f.getParent());
            Files.write(f, new byte[] {1});
        }
        // The sweep covers the whole storage root (test storage may hold leftovers of earlier runs): at least ours.
        assertThat(privacy.sweepEndedAttempts()).isGreaterThanOrEqualTo(1);
        assertThat(Files.exists(liveFile)).as("a live attempt keeps its outputs").isTrue();
        assertThat(Files.exists(endedFile)).isFalse();
    }

    @Test
    void embeddingFilesCanOnlyBeRegisteredInTheFingerprintFolder() {
        UUID p = UUID.fromString(jdbc.queryForObject("INSERT INTO dub_projects (name) VALUES ('x') RETURNING id", String.class));
        assertThatThrownBy(() -> assets.saveAndFlush(new MediaAsset(p, MediaAssetType.SPEAKER_EMBEDDING, "e.npy",
                "projects/" + p + "/dialogue/e.npy", null, 1L, null, null)))
                .hasMessageContaining("ck_media_assets_private_locations");
    }
}
