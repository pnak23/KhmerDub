package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.khmerdub.api.job.AttemptRepository;
import com.khmerdub.api.job.ChunkRepository;
import com.khmerdub.api.job.EngineExceptions.FencedException;
import com.khmerdub.api.job.EngineExceptions.InvalidOutputException;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEngine.DispatchOrder;
import com.khmerdub.api.job.JobEnums.AttemptStatus;
import com.khmerdub.api.job.JobEnums.ChunkStatus;
import com.khmerdub.api.job.JobEnums.EndReason;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobEnums.Stage;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.job.ProcessingAttempt;
import com.khmerdub.api.job.ProcessingChunk;
import com.khmerdub.api.job.ProcessingJob;
import com.khmerdub.api.job.WorkerProtocol.Action;
import com.khmerdub.api.job.WorkerProtocol.ClaimResponse;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.FailRequest;
import com.khmerdub.api.job.WorkerProtocol.Output;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.project.ProjectStatus;
import com.khmerdub.api.project.ProjectViews.StartExtractionRequest;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The job engine: lifecycle, fencing, leases, retries, pause/resume/cancel, publishing. */
@IntegrationTest
class JobEngineTest {

    private static final long MIN = 60_000;

    @Autowired JobEngine engine;
    @Autowired JobRepository jobs;
    @Autowired ChunkRepository chunks;
    @Autowired AttemptRepository attempts;
    @Autowired MediaAssetRepository assets;
    @Autowired DubProjectRepository projectRepo;
    @Autowired ProjectService projects;
    @Autowired StorageService storage;
    @Autowired JdbcTemplate jdbc;

    EngineFixtures fx;

    @BeforeEach
    void setUp() {
        fx = new EngineFixtures(projects, assets, storage, engine, jdbc);
        fx.reset();
    }

    // ------------------------------------------------------------------------------------------ happy paths

    @Test
    void inspectionImportsTheMovie() throws Exception {
        UUID p = fx.projectWithMovie("movie.mp4");
        ProcessingJob job = engine.createJob(p, JobType.IMPORT, Map.of());
        assertThat(engine.admitQueued()).isEqualTo(1);
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        assertThat(order.stage()).isEqualTo(Stage.INSPECT_MEDIA);

        ClaimResponse claim = engine.claim(order.attemptId(), "w1");
        assertThat(claim.workDir()).isEqualTo("projects/" + p + "/temp/attempts/" + order.attemptId());
        assertThat(claim.input()).containsKey("originalPath");
        OffsetDateTime leaseBefore = attempts.findById(order.attemptId()).orElseThrow().getLeaseExpiresAt();
        Thread.sleep(20);
        assertThat(engine.heartbeat(order.attemptId(), 50).action()).isEqualTo(Action.CONTINUE);
        assertThat(attempts.findById(order.attemptId()).orElseThrow().getLeaseExpiresAt()).isAfter(leaseBefore);

        engine.complete(order.attemptId(), new CompleteRequest(List.of(), EngineFixtures.probe(25 * MIN, true), Map.of()));

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.COMPLETED);
        DubProject project = projectRepo.findById(p).orElseThrow();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.IMPORTED);
        assertThat(project.getDurationMs()).isEqualTo(25 * MIN);
        assertThat(project.getSourceAudioStreamIndex()).isEqualTo(1); // the default-disposition stream
        MediaAsset original = assets.findFirstByProjectIdAndType(p, MediaAssetType.ORIGINAL_VIDEO).orElseThrow();
        assertThat(original.getMetadata()).containsKeys("probe", "validation");
    }

    @Test
    void movieWithoutAudioFailsTheImportWithAClearReason() throws Exception {
        UUID p = fx.projectWithMovie("silent.mp4");
        ProcessingJob job = fx.inspect(p, EngineFixtures.probe(10 * MIN, false));
        ProcessingJob after = jobs.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getErrorMessage()).contains("no audio stream");
        assertThat(projectRepo.findById(p).orElseThrow().getStatus()).isEqualTo(ProjectStatus.CREATED);
    }

    @Test
    void movieLongerThanTheProfileLimitIsRejected() throws Exception {
        UUID p = fx.projectWithMovie("long.mkv");
        ProcessingJob job = fx.inspect(p, Map.of("formatName", "matroska,webm", "durationMs", 181 * MIN,
                "videoStreams", List.of(Map.of("index", 0)), "audioStreams", List.of(Map.of("index", 1))));
        assertThat(jobs.findById(job.getId()).orElseThrow().getErrorMessage()).contains("limit").contains("180 minutes");
    }

    @Test
    void extractionRunsSegmentsPublishesVersionedFilesThenMerges() throws Exception {
        UUID p = fx.projectWithMovie("movie.mp4");
        fx.inspect(p, EngineFixtures.probe(25 * MIN, true));
        UUID jobId = projects.startExtraction(p, new StartExtractionRequest(2, "ko")).id();
        engine.admitQueued();

        List<ProcessingChunk> segments = chunks.findByJobIdAndStageOrderByChunkIndexAsc(jobId, Stage.EXTRACT_AUDIO);
        assertThat(segments).extracting(ProcessingChunk::getStartMs).containsExactly(0L, 10 * MIN, 20 * MIN);
        assertThat(segments).extracting(ProcessingChunk::getEndMs).containsExactly(10 * MIN, 20 * MIN, 25 * MIN);
        assertThat(segments.getFirst().getParams()).containsEntry("audioStreamIndex", 2)
                .containsEntry("expectedSamples", 9_600_000);

        for (int i = 0; i < 3; i++) {
            DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
            ClaimResponse claim = engine.claim(order.attemptId(), "w1");
            Output out = fx.audioOutput(claim, "segment.wav", "pcm-" + i);
            engine.complete(order.attemptId(), fx.completion(out));
            String expected = "projects/%s/extracted/%s/segments/%04d-%s.wav".formatted(p, jobId, i, order.attemptId());
            MediaAsset published = assets.findById(chunks.findById(chunks.findByJobIdAndStageOrderByChunkIndexAsc(
                    jobId, Stage.EXTRACT_AUDIO).get(i).getId()).orElseThrow().getResultAssetId()).orElseThrow();
            assertThat(published.getStoragePath()).isEqualTo(expected);
            assertThat(published.getProducedByAttemptId()).isEqualTo(order.attemptId());
            assertThat(Files.readString(storage.resolve(expected))).isEqualTo("pcm-" + i);
            assertThat(Files.exists(storage.resolve(out.path()))).as("moved, not copied").isFalse();
        }

        ProcessingJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getCurrentStage()).isEqualTo(Stage.MERGE_AUDIO);
        ProcessingChunk merge = chunks.findByJobIdAndStageOrderByChunkIndexAsc(jobId, Stage.MERGE_AUDIO).getFirst();
        @SuppressWarnings("unchecked")
        List<String> segmentPaths = (List<String>) merge.getParams().get("segmentPaths");
        assertThat(segmentPaths).hasSize(3).allMatch(s -> s.contains("/segments/"));
        assertThat(segmentPaths.get(0)).contains("/0000-");
        assertThat(segmentPaths.get(2)).contains("/0002-");

        DispatchOrder mergeOrder = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse mergeClaim = engine.claim(mergeOrder.attemptId(), "w1");
        engine.complete(mergeOrder.attemptId(), fx.completion(fx.audioOutput(mergeClaim, "audio.wav", "merged")));

        job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.getProgress()).isEqualTo(100.0);
        MediaAsset result = assets.findById(job.getResultAssetId()).orElseThrow();
        assertThat(result.getType()).isEqualTo(MediaAssetType.EXTRACTED_AUDIO);
        assertThat(result.getStoragePath()).endsWith("audio-16k-mono-" + mergeOrder.attemptId() + ".wav");
        DubProject project = projectRepo.findById(p).orElseThrow();
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.AUDIO_READY);
        assertThat(project.getSourceLanguage()).isEqualTo("ko");
    }

    // ------------------------------------------------------------------------------------- fencing & leases

    @Test
    void afterLeaseExpiryTheOldAttemptIsFencedAndCannotPublish() throws Exception {
        UUID chunkId = startedExtractionChunk();
        DispatchOrder first = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse oldClaim = engine.claim(first.attemptId(), "old-worker");
        Output oldOutput = fx.audioOutput(oldClaim, "segment.wav", "old");

        fx.expireLease(first.attemptId());
        assertThat(engine.overdueAttempts()).contains(first.attemptId());
        assertThat(engine.expire(first.attemptId())).isTrue();
        ProcessingAttempt lost = attempts.findById(first.attemptId()).orElseThrow();
        assertThat(lost.getStatus()).isEqualTo(AttemptStatus.LOST);
        assertThat(lost.getEndReason()).isEqualTo(EndReason.LEASE_EXPIRED);
        assertThat(chunks.findById(chunkId).orElseThrow().getRetryCount()).isEqualTo(1);

        DispatchOrder second = engine.reserveDispatches("cpu", 1).getFirst();
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        ClaimResponse newClaim = engine.claim(second.attemptId(), "new-worker");

        // The old execution is still alive somewhere: everything it does is rejected.
        assertThatThrownBy(() -> engine.heartbeat(first.attemptId(), 90)).isInstanceOf(FencedException.class);
        assertThatThrownBy(() -> engine.complete(first.attemptId(), fx.completion(oldOutput)))
                .isInstanceOf(FencedException.class);
        assertThat(Files.exists(storage.resolve(oldOutput.path()))).as("stale output not published").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_events WHERE event_type = 'STALE_RESULT_REJECTED'",
                Integer.class)).isEqualTo(1);

        engine.complete(second.attemptId(), fx.completion(fx.audioOutput(newClaim, "segment.wav", "new")));
        ProcessingChunk chunk = chunks.findById(chunkId).orElseThrow();
        assertThat(chunk.getStatus()).isEqualTo(ChunkStatus.COMPLETED);
        MediaAsset published = assets.findById(chunk.getResultAssetId()).orElseThrow();
        assertThat(published.getProducedByAttemptId()).isEqualTo(second.attemptId());
        assertThat(Files.readString(storage.resolve(published.getStoragePath()))).isEqualTo("new");
    }

    @Test
    void sameCompletionTwiceIsAcceptedOnce() throws Exception {
        startedExtractionChunk();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse claim = engine.claim(order.attemptId(), "w1");
        CompleteRequest done = fx.completion(fx.audioOutput(claim, "segment.wav", "x"));
        engine.complete(order.attemptId(), done);
        engine.complete(order.attemptId(), done); // e.g. the worker retried after a timeout
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE asset_type = 'AUDIO_CHUNK'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void redeliveredTaskRetiresTheAttemptAndANewOneIsDispatched() throws Exception {
        UUID chunkId = startedExtractionChunk();
        DispatchOrder first = engine.reserveDispatches("cpu", 1).getFirst();
        engine.claim(first.attemptId(), "w1");
        // The worker process crashed; Celery delivers the same message again.
        assertThatThrownBy(() -> engine.claim(first.attemptId(), "w1")).isInstanceOf(FencedException.class);
        ProcessingAttempt retired = attempts.findById(first.attemptId()).orElseThrow();
        assertThat(retired.getStatus()).isEqualTo(AttemptStatus.LOST);
        assertThat(retired.getEndReason()).isEqualTo(EndReason.REDELIVERED);

        DispatchOrder second = engine.reserveDispatches("cpu", 1).getFirst();
        assertThat(attempts.findById(second.attemptId()).orElseThrow().getAttemptNo()).isEqualTo(2);
        assertThat(chunks.findById(chunkId).orElseThrow().getCurrentAttemptId()).isEqualTo(second.attemptId());
    }

    @Test
    void neverClaimedTaskTimesOutWithoutUsingAnAttempt() throws Exception {
        UUID chunkId = startedExtractionChunk();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        fx.expireLease(order.attemptId()); // claim deadline passed (e.g. the worker is not running)
        assertThat(engine.expire(order.attemptId())).isTrue();
        ProcessingAttempt a = attempts.findById(order.attemptId()).orElseThrow();
        assertThat(a.getEndReason()).isEqualTo(EndReason.CLAIM_TIMEOUT);
        assertThat(a.isCountsTowardLimit()).isFalse();
        ProcessingChunk chunk = chunks.findById(chunkId).orElseThrow();
        assertThat(chunk.getRetryCount()).isZero();
        assertThat(chunk.getNotBefore()).isAfter(OffsetDateTime.now());
        // The backed-off chunk waits; the next chunk of the job may run meanwhile.
        DispatchOrder next = engine.reserveDispatches("cpu", 1).getFirst();
        assertThat(attempts.findById(next.attemptId()).orElseThrow().getChunkId()).isNotEqualTo(chunkId);
    }

    @Test
    void startupGraceProtectsRunningAttemptsAfterAnApiRestart() throws Exception {
        startedExtractionChunk();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        engine.claim(order.attemptId(), "w1");
        fx.expireLease(order.attemptId()); // the API was down longer than the lease
        engine.applyStartupGrace();
        assertThat(engine.overdueAttempts()).doesNotContain(order.attemptId());
        assertThat(engine.heartbeat(order.attemptId(), 40).action()).isEqualTo(Action.CONTINUE);
    }

    // ---------------------------------------------------------------------------------------- failures

    @Test
    void repeatedFailuresFailTheJobAndRetryGivesAFreshBudget() throws Exception {
        UUID chunkId = startedExtractionChunk();
        UUID jobId = chunks.findById(chunkId).orElseThrow().getJobId();
        for (int i = 1; i <= 3; i++) {
            fx.clearBackoff();
            DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
            engine.claim(order.attemptId(), "w1");
            engine.fail(order.attemptId(), new FailRequest(true, false, "ffmpeg exited with code 1 (try " + i + ")", Map.of()));
        }
        ProcessingJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(job.getErrorMessage()).contains("EXTRACT_AUDIO #0 failed after 3 attempts").contains("try 3");

        engine.retry(jobId);
        engine.admitQueued();
        ProcessingChunk chunk = chunks.findById(chunkId).orElseThrow();
        assertThat(chunk.getStatus()).isEqualTo(ChunkStatus.PENDING);
        assertThat(chunk.getRetryCount()).isZero();
        assertThat(engine.reserveDispatches("cpu", 1)).hasSize(1);
    }

    @Test
    void nonRetryableFailureFailsImmediately() throws Exception {
        UUID chunkId = startedExtractionChunk();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        engine.claim(order.attemptId(), "w1");
        engine.fail(order.attemptId(), new FailRequest(false, false, "Audio stream 2 cannot be decoded", Map.of()));
        assertThat(jobs.findById(chunks.findById(chunkId).orElseThrow().getJobId()).orElseThrow().getStatus())
                .isEqualTo(JobStatus.FAILED);
    }

    @Test
    void wrongChecksumIsRejectedAndRetried() throws Exception {
        UUID chunkId = startedExtractionChunk();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse claim = engine.claim(order.attemptId(), "w1");
        Output good = fx.audioOutput(claim, "segment.wav", "data");
        Output corrupt = new Output(good.role(), good.path(), good.sizeBytes(), "0".repeat(64), good.info());
        assertThatThrownBy(() -> engine.complete(order.attemptId(), fx.completion(corrupt)))
                .isInstanceOf(InvalidOutputException.class).hasMessageContaining("Checksum");
        assertThat(attempts.findById(order.attemptId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.FAILED);
        assertThat(chunks.findById(chunkId).orElseThrow().getStatus()).isEqualTo(ChunkStatus.PENDING);
    }

    @Test
    void publishingNeverOverwritesAnExistingFile() throws Exception {
        UUID chunkId = startedExtractionChunk();
        UUID jobId = chunks.findById(chunkId).orElseThrow().getJobId();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse claim = engine.claim(order.attemptId(), "w1");
        Path target = storage.resolve("projects/%s/extracted/%s/segments/0000-%s.wav"
                .formatted(claim.projectId(), jobId, order.attemptId()));
        Files.createDirectories(target.getParent());
        Files.writeString(target, "precious");
        assertThatThrownBy(() -> engine.complete(order.attemptId(), fx.completion(fx.audioOutput(claim, "s.wav", "new"))))
                .isInstanceOf(InvalidOutputException.class).hasMessageContaining("Refusing to overwrite");
        assertThat(Files.readString(target)).isEqualTo("precious");
    }

    @Test
    void outputsOutsideTheAttemptFolderAreRejected() throws Exception {
        startedExtractionChunk();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse claim = engine.claim(order.attemptId(), "w1");
        Output good = fx.audioOutput(claim, "segment.wav", "data");
        Output elsewhere = new Output("audio", "projects/" + claim.projectId() + "/original/movie.mp4",
                good.sizeBytes(), good.sha256(), good.info());
        assertThatThrownBy(() -> engine.complete(order.attemptId(), fx.completion(elsewhere)))
                .isInstanceOf(InvalidOutputException.class).hasMessageContaining("outside");
    }

    // ------------------------------------------------------------------------------- pause/resume/cancel

    @Test
    void pauseLetsRunningWorkFinishAndResumeContinuesWithTheNextChunk() throws Exception {
        UUID chunkId = startedExtractionChunk();
        UUID jobId = chunks.findById(chunkId).orElseThrow().getJobId();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse claim = engine.claim(order.attemptId(), "w1");

        engine.pause(jobId);
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.PAUSING);
        assertThat(engine.reserveDispatches("cpu", 2)).isEmpty();

        engine.complete(order.attemptId(), fx.completion(fx.audioOutput(claim, "segment.wav", "seg0")));
        assertThat(chunks.findById(chunkId).orElseThrow().getStatus()).isEqualTo(ChunkStatus.COMPLETED);
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.PAUSED);
        assertThat(engine.reserveDispatches("cpu", 1)).isEmpty();

        engine.resume(jobId);
        engine.admitQueued();
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        DispatchOrder next = engine.reserveDispatches("cpu", 1).getFirst();
        assertThat(chunks.findById(attempts.findById(next.attemptId()).orElseThrow().getChunkId()).orElseThrow()
                .getChunkIndex()).isEqualTo(1);
    }

    @Test
    void cancelStopsRunningWorkAndDiscardsItsResult() throws Exception {
        UUID chunkId = startedExtractionChunk();
        UUID jobId = chunks.findById(chunkId).orElseThrow().getJobId();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        ClaimResponse claim = engine.claim(order.attemptId(), "w1");

        engine.cancel(jobId);
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLING);
        assertThat(engine.heartbeat(order.attemptId(), 30).action()).isEqualTo(Action.CANCEL);
        assertThatThrownBy(() -> engine.complete(order.attemptId(), fx.completion(fx.audioOutput(claim, "s.wav", "late"))))
                .isInstanceOf(FencedException.class);

        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(chunks.findByJobId(jobId)).allMatch(c -> c.getStatus() == ChunkStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE asset_type = 'AUDIO_CHUNK'",
                Integer.class)).isZero();
    }

    @Test
    void workerAcknowledgedCancelEndsTheJob() throws Exception {
        UUID chunkId = startedExtractionChunk();
        UUID jobId = chunks.findById(chunkId).orElseThrow().getJobId();
        DispatchOrder order = engine.reserveDispatches("cpu", 1).getFirst();
        engine.claim(order.attemptId(), "w1");
        engine.cancel(jobId);
        engine.fail(order.attemptId(), new FailRequest(false, true, "Cancelled on request", Map.of()));
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.CANCELLED);
        assertThat(attempts.findById(order.attemptId()).orElseThrow().getStatus()).isEqualTo(AttemptStatus.CANCELLED);
    }

    @Test
    void onlyOneHeavyJobRunsAtATime() throws Exception {
        UUID p1 = fx.projectWithMovie("a.mp4");
        UUID p2 = fx.projectWithMovie("b.mp4");
        fx.inspect(p1, EngineFixtures.probe(30 * MIN, true));
        fx.inspect(p2, EngineFixtures.probe(30 * MIN, true));
        UUID j1 = projects.startExtraction(p1, new StartExtractionRequest(null, null)).id();
        UUID j2 = projects.startExtraction(p2, new StartExtractionRequest(null, null)).id();
        engine.admitQueued();
        assertThat(jobs.findById(j1).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(jobs.findById(j2).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
    }

    @Test
    void invalidTransitionsAreRefused() throws Exception {
        UUID chunkId = startedExtractionChunk();
        UUID jobId = chunks.findById(chunkId).orElseThrow().getJobId();
        assertThatThrownBy(() -> engine.resume(jobId)).hasMessageContaining("Cannot resume a job that is running");
        assertThatThrownBy(() -> engine.retry(jobId)).hasMessageContaining("Cannot retry");
    }

    // -------------------------------------------------------------------------------------------------

    /** An inspected 25-minute movie with a running extraction job; returns the first segment's chunk id. */
    private UUID startedExtractionChunk() throws Exception {
        UUID p = fx.projectWithMovie("movie-" + UUID.randomUUID() + ".mp4");
        fx.inspect(p, EngineFixtures.probe(25 * MIN, true));
        UUID jobId = projects.startExtraction(p, new StartExtractionRequest(null, null)).id();
        engine.admitQueued();
        return chunks.findByJobIdAndStageOrderByChunkIndexAsc(jobId, Stage.EXTRACT_AUDIO).getFirst().getId();
    }
}
