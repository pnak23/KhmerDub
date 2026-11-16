package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.khmerdub.api.dialogue.DialogueRevision;
import com.khmerdub.api.dialogue.DialogueRevisionRepository;
import com.khmerdub.api.dialogue.DialogueSegment;
import com.khmerdub.api.dialogue.DialogueSegmentRepository;
import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.dialogue.Speaker;
import com.khmerdub.api.dialogue.SpeakerRepository;
import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobEnums.Stage;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Reprocessing can never silently destroy reviewed dialogue or speaker assignments. */
@IntegrationTest
class ReprocessSafetyTest {

    static final long MIN = 60_000;

    @Autowired JobEngine engine;
    @Autowired JobRepository jobs;
    @Autowired MediaAssetRepository assets;
    @Autowired ProjectService projects;
    @Autowired TranscriptionService transcription;
    @Autowired DialogueRevisionRepository revisions;
    @Autowired SpeakerRepository speakers;
    @Autowired DialogueSegmentRepository segments;
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

    /** A project with an ACTIVE revision whose dialogue and speakers the user has reviewed and edited. */
    private UUID reviewedProject() throws Exception {
        UUID p = sx.audioReadyProject(12 * MIN);
        assertThat(sx.run(sx.start(p, new StartTranscriptionRequest(null, null, null, false)), null, "-v1"))
                .isEqualTo(JobStatus.COMPLETED);
        UUID rev = active(p).getId();
        jdbc.update("UPDATE dialogue_segments SET source_text_edited = '你好啊', edited_text = 'សួស្តី', "
                + "review_status = 'APPROVED', speaker_assignment = 'MANUAL' WHERE revision_id = ? AND sequence_no = 0", rev);
        jdbc.update("UPDATE speakers SET character_name = 'Pei', review_status = 'REVIEWED' WHERE revision_id = ? "
                + "AND speaker_code = 'SPK_01'", rev);
        return p;
    }

    private DialogueRevision active(UUID p) {
        return revisions.findFirstByProjectIdAndStatus(p, DialogueRevision.Status.ACTIVE).orElseThrow();
    }

    /** Everything the user could have reviewed, in a stable order (to compare before/after byte for byte). */
    private String snapshot(UUID revisionId) {
        List<Map<String, Object>> sp = speakers.findByRevisionIdOrderByDisplayOrderAsc(revisionId).stream()
                .map(Speaker::export).toList();
        List<Map<String, Object>> ln = segments.findByRevisionIdOrderBySequenceNoAsc(revisionId).stream()
                .map(DialogueSegment::export).toList();
        return json.writeValueAsString(Map.of("speakers", sp, "lines", ln));
    }

    @Test
    void reprocessingRequiresConfirmationAndNothingHappensWithout() throws Exception {
        UUID p = reviewedProject();
        long jobsBefore = jobs.count();
        assertThatThrownBy(() -> transcription.start(p, new StartTranscriptionRequest(null, null, null, false)))
                .isInstanceOf(ApiException.class).hasMessageContaining("confirm");
        assertThat(jobs.count()).isEqualTo(jobsBefore);
        assertThat(assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p, MediaAssetType.DIALOGUE_BACKUP)).isEmpty();
    }

    @Test
    void confirmedReprocessWritesAVerifiedBackupAndAFailedRunLeavesTheActiveDialogueUntouched() throws Exception {
        UUID p = reviewedProject();
        DialogueRevision before = active(p);
        String reviewed = snapshot(before.getId());

        UUID jobId = sx.start(p, new StartTranscriptionRequest(null, null, null, true));
        MediaAsset backup = assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p, MediaAssetType.DIALOGUE_BACKUP).getFirst();
        assertThat(jobs.findById(jobId).orElseThrow().getParameters()).containsEntry("backupAssetId", backup.getId().toString());
        assertThat(backup.getMetadata()).containsEntry("verified", true).containsEntry("lines", 3);
        var file = storage.resolve(backup.getStoragePath());
        assertThat(StorageService.sha256(file)).isEqualTo(backup.getSha256());
        Map<String, Object> doc = json.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { });
        assertThat(doc.get("format")).isEqualTo("khmerdub.dialogue-backup.v1");
        assertThat(doc.toString()).contains("你好啊").contains("សួស្តី").contains("Pei").contains("MANUAL");

        // The new run fails in the middle (after transcription, during speaker identification).
        assertThat(sx.run(jobId, Stage.DIARIZE, "-v2")).isEqualTo(JobStatus.FAILED);
        assertThat(active(p).getId()).isEqualTo(before.getId());
        assertThat(snapshot(before.getId())).as("reviewed dialogue byte-identical").isEqualTo(reviewed);
        assertThat(revisions.findByProjectIdOrderByRevisionNoDesc(p)).hasSize(1);
    }

    @Test
    void aSuccessfulRerunWaitsAsReadyAndSwitchingIsConfirmedBackedUpAndReversible() throws Exception {
        UUID p = reviewedProject();
        DialogueRevision first = active(p);
        String reviewed = snapshot(first.getId());
        assertThat(sx.run(sx.start(p, new StartTranscriptionRequest(null, null, null, true)), null, "-v2"))
                .isEqualTo(JobStatus.COMPLETED);

        DialogueRevision second = revisions.findByProjectIdOrderByRevisionNoDesc(p).getFirst();
        assertThat(second.getRevisionNo()).isEqualTo(2);
        assertThat(second.getStatus()).as("never activated implicitly").isEqualTo(DialogueRevision.Status.READY);
        assertThat(active(p).getId()).isEqualTo(first.getId());
        assertThat(snapshot(first.getId())).isEqualTo(reviewed);

        assertThatThrownBy(() -> transcription.activate(p, second.getId(), false))
                .isInstanceOf(ApiException.class).hasMessageContaining("confirm");
        assertThat(active(p).getId()).isEqualTo(first.getId());

        transcription.activate(p, second.getId(), true);
        assertThat(active(p).getId()).isEqualTo(second.getId());
        DialogueRevision old = revisions.findById(first.getId()).orElseThrow();
        assertThat(old.getStatus()).isEqualTo(DialogueRevision.Status.SUPERSEDED);
        assertThat(snapshot(first.getId())).as("the reviewed revision is kept intact").isEqualTo(reviewed);
        assertThat(assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p, MediaAssetType.DIALOGUE_BACKUP)).hasSize(2)
                .allSatisfy(a -> assertThat(Files.exists(storage.resolve(a.getStoragePath()))).isTrue());
        assertThat(jobs.findByProjectIdOrderByCreatedAtDesc(p)).filteredOn(j -> j.getType() == JobType.TRANSCRIBE).hasSize(2);
    }
}
