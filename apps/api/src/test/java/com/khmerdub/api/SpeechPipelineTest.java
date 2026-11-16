package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.khmerdub.api.dialogue.DialogueRevision;
import com.khmerdub.api.dialogue.DialogueRevisionRepository;
import com.khmerdub.api.dialogue.DialogueSegmentRepository;
import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.dialogue.SpeakerRepository;
import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.job.ChunkRepository;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEngine.DispatchOrder;
import com.khmerdub.api.job.JobEnums.ChunkStatus;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.Stage;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.job.ProcessingChunk;
import com.khmerdub.api.job.WorkerProtocol.ClaimResponse;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.power.PowerKeeper;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.project.ProjectStatus;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** The TRANSCRIBE job end to end through the engine, with a simulated speech worker. */
@IntegrationTest
class SpeechPipelineTest {

    static final long MIN = 60_000;

    @Autowired JobEngine engine;
    @Autowired JobRepository jobs;
    @Autowired ChunkRepository chunks;
    @Autowired MediaAssetRepository assets;
    @Autowired DubProjectRepository projectRepo;
    @Autowired ProjectService projects;
    @Autowired TranscriptionService transcription;
    @Autowired DialogueRevisionRepository revisions;
    @Autowired SpeakerRepository speakers;
    @Autowired DialogueSegmentRepository segments;
    @Autowired StorageService storage;
    @Autowired PowerKeeper power;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;

    EngineFixtures fx;
    SpeechFixtures sx;

    @BeforeEach
    void setUp() {
        fx = new EngineFixtures(projects, assets, storage, engine, jdbc);
        fx.reset();
        sx = new SpeechFixtures(fx, engine, jobs, projects, transcription, storage, json);
    }

    @Test
    void fullRunPlansStagesImportsAnActiveRevisionAndKeepsEmbeddingsPrivate() throws Exception {
        UUID p = sx.audioReadyProject(25 * MIN);
        UUID jobId = sx.start(p, new StartTranscriptionRequest(null, null, null, false));
        assertThat(sx.run(jobId, null, "")).isEqualTo(JobStatus.COMPLETED);

        // Transcription: ~5 min cores, boundaries moved into silences (295-305 s and 900-905 s), 2.5 s padding.
        List<ProcessingChunk> asr = chunks.findByJobIdAndStageOrderByChunkIndexAsc(jobId, Stage.TRANSCRIBE);
        assertThat(asr.getFirst().getParams()).containsEntry("coreStartMs", 0).containsEntry("coreEndMs", 300_000)
                .containsEntry("language", "zh").containsEntry("beamSize", 5).containsEntry("threads", 4);
        // Cores: 0-300 s (silence at 295-305), 300-600 s (no silence near 600 s: target kept), 600-902.5 s
        // (silence at 900-905), 902.5-1202.5 s, 1202.5-1500 s (the remainder is not split into a tiny chunk).
        assertThat(asr).hasSize(5);
        assertThat(asr.get(1).getParams()).containsEntry("startMs", 297_500).containsEntry("coreStartMs", 300_000);
        assertThat(asr.get(2).getParams()).containsEntry("coreStartMs", 600_000).containsEntry("coreEndMs", 902_500);
        assertThat(asr.get(3).getParams()).containsEntry("coreStartMs", 902_500);
        assertThat(asr.getLast().getParams()).containsEntry("coreEndMs", 1_500_000).containsEntry("endMs", 1_500_000);
        for (int i = 1; i < asr.size(); i++) {
            assertThat(asr.get(i).getParams().get("coreStartMs")).as("cores are contiguous")
                    .isEqualTo(asr.get(i - 1).getParams().get("coreEndMs"));
        }
        // Diarization: exactly 10 min chunks with 60 s overlap, Quality step 0.1, 8 threads.
        List<ProcessingChunk> dia = chunks.findByJobIdAndStageOrderByChunkIndexAsc(jobId, Stage.DIARIZE);
        assertThat(dia).extracting(ProcessingChunk::getStartMs).containsExactly(0L, 540_000L, 1_080_000L);
        assertThat(dia).extracting(ProcessingChunk::getEndMs).containsExactly(600_000L, 1_140_000L, 1_500_000L);
        assertThat(dia.getFirst().getParams()).containsEntry("segmentationStep", 0.1).containsEntry("threads", 8);

        // Engine-internal params never reach the worker.
        ClaimResponse reconcile = sx.claims.get(Stage.RECONCILE_SPEAKERS).getFirst();
        assertThat(reconcile.input().keySet()).noneMatch(k -> k.startsWith("_"));
        assertThat(reconcile.input().toString()).contains("/private/fingerprints/");

        // A first run becomes the ACTIVE revision; the project is TRANSCRIBED.
        DialogueRevision rev = revisions.findFirstByProjectIdAndStatus(p, DialogueRevision.Status.ACTIVE).orElseThrow();
        assertThat(rev.getSourceJobId()).isEqualTo(jobId);
        assertThat(rev.getDiarizationMode()).isEqualTo("QUALITY");
        assertThat(speakers.countByRevisionId(rev.getId())).isEqualTo(2);
        assertThat(segments.countByRevisionId(rev.getId())).isEqualTo(3);
        assertThat(projectRepo.findById(p).orElseThrow().getStatus()).isEqualTo(ProjectStatus.TRANSCRIBED);
        assertThat(projectRepo.findById(p).orElseThrow().getDetectedLanguage()).isEqualTo("zh");

        // Embeddings live only under private/fingerprints and are never served (404 like a missing asset).
        List<MediaAsset> embeddings = assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p, MediaAssetType.SPEAKER_EMBEDDING);
        assertThat(embeddings).hasSize(4).allMatch(a -> a.getStoragePath().startsWith("projects/" + p + "/private/fingerprints/"));
        for (MediaAsset e : embeddings) {
            assertThat(Files.exists(storage.resolve(e.getStoragePath()))).isTrue();
            mvc.perform(get("/api/assets/" + e.getId())).andExpect(status().isNotFound());
            mvc.perform(get("/api/assets/" + e.getId() + "/content")).andExpect(status().isNotFound());
        }
        // Voice samples are separate and playable.
        List<MediaAsset> samples = assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p, MediaAssetType.SPEAKER_SAMPLE);
        assertThat(samples).hasSize(2).allMatch(a -> a.getStoragePath().startsWith("projects/" + p + "/private/voice-samples/"));
        mvc.perform(get("/api/assets/" + samples.getFirst().getId() + "/content")).andExpect(status().isOk());

        // No response (job view = WebSocket payload, speakers, dialogue) mentions embeddings or their folder.
        String jobJson = mvc.perform(get("/api/jobs/" + jobId)).andReturn().getResponse().getContentAsString();
        String speakerJson = mvc.perform(get("/api/projects/" + p + "/speakers")).andReturn().getResponse().getContentAsString();
        String dialogueJson = mvc.perform(get("/api/projects/" + p + "/dialogue")).andReturn().getResponse().getContentAsString();
        for (String body : List.of(jobJson, speakerJson, dialogueJson)) {
            assertThat(body.toLowerCase()).doesNotContain("fingerprints").doesNotContain("embedding").doesNotContain(".npy");
        }
        assertThat(speakerJson).contains("SPK_01").contains(samples.getFirst().getId().toString());

        // Private outputs of every attempt were removed from the scratch folders.
        try (var walk = Files.walk(storage.resolve(StorageService.projectDir(p) + "/temp/attempts"))) {
            assertThat(walk.filter(Files::isRegularFile).map(Object::toString).toList())
                    .noneMatch(s -> s.contains("private"));
        }
    }

    @Test
    void fastModeForcedLanguageAndSpeakerIdentificationOff() throws Exception {
        UUID p = sx.audioReadyProject(12 * MIN);
        UUID fast = sx.start(p, new StartTranscriptionRequest("FAST", "zh", null, false));
        assertThat(sx.run(fast, null, "")).isEqualTo(JobStatus.COMPLETED);
        assertThat(chunks.findByJobIdAndStageOrderByChunkIndexAsc(fast, Stage.DETECT_LANGUAGE).getFirst().getStatus())
                .as("language was chosen, detection skipped").isEqualTo(ChunkStatus.SKIPPED);
        assertThat(chunks.findByJobIdAndStageOrderByChunkIndexAsc(fast, Stage.DIARIZE).getFirst().getParams())
                .containsEntry("segmentationStep", 0.2);

        UUID p2 = sx.audioReadyProject(12 * MIN);
        UUID off = sx.start(p2, new StartTranscriptionRequest(null, null, false, false));
        assertThat(sx.run(off, null, "")).isEqualTo(JobStatus.COMPLETED);
        assertThat(chunks.findByJobIdAndStageOrderByChunkIndexAsc(off, Stage.DIARIZE)).extracting(ProcessingChunk::getStatus)
                .containsExactly(ChunkStatus.SKIPPED);
        assertThat(chunks.findByJobIdAndStageOrderByChunkIndexAsc(off, Stage.RECONCILE_SPEAKERS)).extracting(ProcessingChunk::getStatus)
                .containsExactly(ChunkStatus.SKIPPED);
        assertThat(chunks.findByJobIdAndStageOrderByChunkIndexAsc(off, Stage.BUILD_DIALOGUE).getFirst().getParams())
                .doesNotContainKey("speakersPath");
        assertThat(assets.findByProjectIdAndTypeOrderByCreatedAtAsc(p2, MediaAssetType.SPEAKER_EMBEDDING)).isEmpty();
    }

    @Test
    void keepAwakeDecisionFollowsTheJob() throws Exception {
        UUID p = sx.audioReadyProject(12 * MIN);
        UUID jobId = sx.start(p, new StartTranscriptionRequest(null, null, null, false));
        engine.admitQueued();
        DispatchOrder o = engine.reserveDispatches("cpu", 1).getFirst();
        engine.claim(o.attemptId(), "w");
        engine.heartbeat(o.attemptId(), 10);
        assertThat(power.evaluate().hold()).as("RUNNING with a fresh worker").isTrue();
        assertThat(power.snapshot()).singleElement().satisfies(s -> {
            assertThat(s.status()).isEqualTo("RUNNING");
            assertThat(s.lastWorkerSignal()).isNotNull();
            assertThat(s.lastProgress()).isNotNull();
            assertThat(s.liveAttempts()).isEqualTo(1);
        });
        engine.pause(jobId);
        assertThat(power.evaluate().hold()).as("PAUSING while the chunk finishes").isTrue();
        engine.fail(o.attemptId(), new com.khmerdub.api.job.WorkerProtocol.FailRequest(false, true, "stopped",
                java.util.Map.of()));
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.PAUSED);
        assertThat(power.evaluate().hold()).as("PAUSED never holds").isFalse();

        engine.resume(jobId);
        assertThat(sx.run(jobId, null, "")).isEqualTo(JobStatus.COMPLETED);
        assertThat(power.evaluate().hold()).as("finished jobs never hold").isFalse();
    }
}
