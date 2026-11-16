package com.khmerdub.api.job;

import com.khmerdub.api.config.KhmerDubProperties;
import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.EngineExceptions.AttemptNotFoundException;
import com.khmerdub.api.job.EngineExceptions.FencedException;
import com.khmerdub.api.job.EngineExceptions.InvalidOutputException;
import com.khmerdub.api.job.EngineExceptions.JobCannotContinueException;
import com.khmerdub.api.job.EngineExceptions.PlanningException;
import com.khmerdub.api.job.JobEnums.AttemptStatus;
import com.khmerdub.api.job.JobEnums.ChunkStatus;
import com.khmerdub.api.job.JobEnums.EndReason;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobEnums.Stage;
import com.khmerdub.api.job.WorkerProtocol.Action;
import com.khmerdub.api.job.WorkerProtocol.ClaimResponse;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.FailRequest;
import com.khmerdub.api.job.WorkerProtocol.HeartbeatResponse;
import com.khmerdub.api.storage.StorageService;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authoritative job state machine. Spring owns job, chunk and attempt state; workers only report.
 *
 * <p>Concurrency: every public operation runs in one transaction and first locks the job row
 * ({@code SELECT ... FOR UPDATE}), so changes within a job are serialised. Lock order: job, chunk, attempt.
 *
 * <p>Fencing: an attempt may heartbeat or publish only while it is the chunk's {@code current_attempt_id}
 * and RUNNING. Anything else gets {@link FencedException} (HTTP 409) and must publish nothing.
 */
@Service
public class JobEngine {

    private static final Logger log = LoggerFactory.getLogger(JobEngine.class);

    private final JobRepository jobs;
    private final ChunkRepository chunks;
    private final AttemptRepository attempts;
    private final JobEventRepository events;
    private final StagePlanner planner;
    private final KhmerDubProperties.Jobs config;
    private final ApplicationEventPublisher publisher;
    private final EntityManager em;
    private final Clock clock;
    private final StorageService storage;

    public JobEngine(JobRepository jobs, ChunkRepository chunks, AttemptRepository attempts,
                     JobEventRepository events, StagePlanner planner, KhmerDubProperties props,
                     ApplicationEventPublisher publisher, EntityManager em, Clock clock, StorageService storage) {
        this.jobs = jobs;
        this.chunks = chunks;
        this.attempts = attempts;
        this.events = events;
        this.planner = planner;
        this.config = props.jobs();
        this.publisher = publisher;
        this.em = em;
        this.clock = clock;
        this.storage = storage;
    }

    /** Published after commit so the UI can refresh (see JobPublisher). */
    public record JobChanged(UUID jobId, UUID projectId) {
    }

    public record DispatchOrder(UUID attemptId, UUID jobId, Stage stage, String queue) {
    }

    private record Locked(ProcessingJob job, ProcessingChunk chunk, ProcessingAttempt attempt) {
        boolean current() {
            return attempt.getId().equals(chunk.getCurrentAttemptId());
        }
    }

    // ===================================================================================== creation & admission

    @Transactional
    public ProcessingJob createJob(UUID projectId, JobType type, Map<String, Object> parameters) {
        if (jobs.existsByProjectIdAndTypeAndStatusIn(projectId, type, JobStatus.ACTIVE)) {
            throw ApiException.conflict("JOB_ALREADY_ACTIVE", "A %s job is already active for this project."
                    .formatted(type.name().toLowerCase().replace('_', ' ')));
        }
        ProcessingJob job = jobs.save(new ProcessingJob(projectId, type, config.maxAttempts(), parameters));
        event(job, null, null, "JOB_QUEUED", JobEvent.Level.INFO, "Job queued", parameters);
        changed(job);
        return job;
    }

    /** Start queued jobs in order. A heavy job (whole-movie work) starts only when no other heavy job is active. */
    @Transactional
    public int admitQueued() {
        boolean heavyBusy = jobs.findByStatusInOrderByCreatedAtAsc(JobStatus.OCCUPYING).stream()
                .anyMatch(j -> j.getType().heavy());
        int admitted = 0;
        for (ProcessingJob candidate : jobs.findByStatusInOrderByCreatedAtAsc(EnumSet.of(JobStatus.QUEUED))) {
            if (candidate.getType().heavy() && heavyBusy) {
                continue;
            }
            ProcessingJob job = lockJob(candidate.getId());
            if (job.getStatus() != JobStatus.QUEUED) {
                continue;
            }
            job.setStatus(JobStatus.RUNNING);
            if (job.getStartedAt() == null) {
                job.setStartedAt(now());
            }
            event(job, null, null, "JOB_STARTED", JobEvent.Level.INFO, "Job started", Map.of());
            if (job.getCurrentStage() == null) {
                enterStage(job, job.getType().stages().getFirst());
            } else {
                advance(job);
            }
            if (job.getType().heavy() && JobStatus.OCCUPYING.contains(job.getStatus())) {
                heavyBusy = true;
            }
            recomputeProgress(job);
            changed(job);
            admitted++;
        }
        return admitted;
    }

    // ===================================================================================== dispatch

    /**
     * Reserve work for free slots: create a new attempt (fencing token) for each chosen chunk and commit it
     * BEFORE the task is sent to the queue. If sending fails, {@link #dispatchFailed} releases the chunk.
     */
    @Transactional
    public List<DispatchOrder> reserveDispatches(String queue, int slots) {
        long live = chunks.countByQueueAndStatusIn(queue, ChunkStatus.LIVE);
        int capacity = (int) Math.max(0, slots - live);
        if (capacity == 0) {
            return List.of();
        }
        OffsetDateTime now = now();
        List<DispatchOrder> orders = new ArrayList<>();
        for (ProcessingChunk candidate : chunks.findDispatchCandidates(queue, now, Limit.of(capacity))) {
            ProcessingJob job = lockJob(candidate.getJobId());
            ProcessingChunk chunk = refresh(candidate);
            if (job.getStatus() != JobStatus.RUNNING || chunk.getStatus() != ChunkStatus.PENDING
                    || chunk.getStage() != job.getCurrentStage()) {
                continue;
            }
            ProcessingAttempt attempt = attempts.save(new ProcessingAttempt(UUID.randomUUID(), chunk.getId(),
                    attempts.maxAttemptNo(chunk.getId()) + 1, now, now.plus(config.claimTimeout())));
            chunk.setStatus(ChunkStatus.DISPATCHED);
            chunk.setCurrentAttemptId(attempt.getId());
            chunk.setNotBefore(null);
            if (chunk.getStartedAt() == null) {
                chunk.setStartedAt(now);
            }
            event(job, chunk, attempt, "ATTEMPT_DISPATCHED", JobEvent.Level.DEBUG,
                    "%s #%d dispatched (attempt %d)".formatted(chunk.getStage(), chunk.getChunkIndex(), attempt.getAttemptNo()),
                    Map.of());
            orders.add(new DispatchOrder(attempt.getId(), job.getId(), chunk.getStage(), chunk.getQueue()));
            changed(job);
        }
        return orders;
    }

    @Transactional
    public void dispatchFailed(UUID attemptId, String error) {
        Locked x = lockByAttempt(attemptId);
        if (x.current() && x.attempt().getStatus().live()) {
            lose(x, EndReason.DISPATCH_FAILED, false, "Could not queue the task: " + error);
        }
    }

    // ===================================================================================== worker protocol

    /** State changes made before a fencing rejection (retiring a redelivered attempt, etc.) must be kept. */
    @Transactional(noRollbackFor = FencedException.class)
    public ClaimResponse claim(UUID attemptId, String worker) {
        Locked x = lockByAttempt(attemptId);
        ProcessingJob job = x.job();
        ProcessingChunk chunk = x.chunk();
        ProcessingAttempt attempt = x.attempt();
        if (!x.current() || !attempt.getStatus().live()) {
            throw new FencedException("Attempt %s is no longer current".formatted(attemptId));
        }
        if (job.getStatus() == JobStatus.CANCELLING || job.getStatus().terminal()) {
            endLive(x, AttemptStatus.CANCELLED, EndReason.CANCELLED, "Job was cancelled before the task started");
            settle(job);
            throw new FencedException("Job is cancelled");
        }
        if (attempt.getStatus() == AttemptStatus.RUNNING) {
            // The same task arrived again: its previous execution died (e.g. the worker process crashed and
            // Celery re-queued the message). Never run one attempt twice: retire it and dispatch a new one.
            lose(x, EndReason.REDELIVERED, true, "Task was delivered again; its previous execution stopped");
            throw new FencedException("Attempt was redelivered; a new attempt will be dispatched");
        }
        OffsetDateTime now = now();
        attempt.setStatus(AttemptStatus.RUNNING);
        attempt.setWorker(worker);
        attempt.setClaimedAt(now);
        attempt.setLastHeartbeatAt(now);
        attempt.reportProgress(0, now);
        attempt.setLeaseExpiresAt(now.plus(config.lease()));
        chunk.setStatus(ChunkStatus.RUNNING);
        event(job, chunk, attempt, "ATTEMPT_STARTED", JobEvent.Level.INFO,
                "%s #%d started on %s (attempt %d)".formatted(chunk.getStage(), chunk.getChunkIndex(), worker,
                        attempt.getAttemptNo()), Map.of("worker", worker));
        changed(job);
        return new ClaimResponse(attempt.getId(), attempt.getAttemptNo(), job.getId(), job.getProjectId(),
                chunk.getStage(), chunk.getChunkIndex(), chunk.getStartMs(), chunk.getEndMs(),
                StorageService.attemptWorkDir(job.getProjectId(), attempt.getId()),
                config.heartbeatInterval().toSeconds(), config.lease().toSeconds(), planner.taskInput(chunk));
    }

    @Transactional(noRollbackFor = FencedException.class)
    public HeartbeatResponse heartbeat(UUID attemptId, double progress) {
        Locked x = lockByAttempt(attemptId);
        if (!x.current() || x.attempt().getStatus() != AttemptStatus.RUNNING) {
            throw new FencedException("Attempt %s is no longer current".formatted(attemptId));
        }
        OffsetDateTime now = now();
        x.attempt().setLastHeartbeatAt(now);
        x.attempt().setLeaseExpiresAt(now.plus(config.lease()));
        x.attempt().reportProgress(progress, now);
        x.chunk().setProgress(progress);
        recomputeProgress(x.job());
        changed(x.job());
        Action action = x.job().getStatus() == JobStatus.CANCELLING ? Action.CANCEL : Action.CONTINUE;
        return new HeartbeatResponse(action, config.lease().toSeconds());
    }

    @Transactional(noRollbackFor = {FencedException.class, InvalidOutputException.class})
    public void complete(UUID attemptId, CompleteRequest result) {
        Locked x = lockByAttempt(attemptId);
        ProcessingJob job = x.job();
        ProcessingChunk chunk = x.chunk();
        ProcessingAttempt attempt = x.attempt();
        if (attempt.getStatus() == AttemptStatus.SUCCEEDED && x.current()) {
            return; // the same completion delivered twice: already accepted
        }
        if (!x.current() || attempt.getStatus() != AttemptStatus.RUNNING) {
            event(job, chunk, attempt, "STALE_RESULT_REJECTED", JobEvent.Level.WARN,
                    "Rejected a result from attempt %d of %s #%d: it is no longer the current attempt"
                            .formatted(attempt.getAttemptNo(), chunk.getStage(), chunk.getChunkIndex()), Map.of());
            changed(job);
            throw new FencedException("Attempt %s is no longer current; result discarded".formatted(attemptId));
        }
        if (job.getStatus() == JobStatus.CANCELLING) {
            endLive(x, AttemptStatus.CANCELLED, EndReason.CANCELLED, "Result discarded: job is being cancelled");
            settle(job);
            throw new FencedException("Job is being cancelled; result discarded");
        }
        OffsetDateTime now = now();
        String cannotContinue = null;
        try {
            planner.accept(job, chunk, attempt, result);
        } catch (InvalidOutputException e) {
            failAttempt(x, EndReason.WORKER_ERROR, true, e.getMessage());
            throw e;
        } catch (JobCannotContinueException e) {
            cannotContinue = e.getMessage();
        }
        attempt.end(AttemptStatus.SUCCEEDED, EndReason.COMPLETED, null, now);
        attempt.setProgress(100);
        attempt.setMetrics(result.metrics() == null ? Map.of() : result.metrics());
        chunk.setStatus(ChunkStatus.COMPLETED);
        chunk.setProgress(100);
        chunk.setCompletedAt(now);
        chunk.setErrorDetails(null);
        event(job, chunk, attempt, "CHUNK_COMPLETED", JobEvent.Level.INFO,
                "%s #%d completed (attempt %d)".formatted(chunk.getStage(), chunk.getChunkIndex(), attempt.getAttemptNo()),
                attempt.getMetrics());
        purgePrivate(job, attempt.getId());
        if (cannotContinue != null) {
            failJob(job, cannotContinue);
        }
        settle(job);
        changed(job);
    }

    @Transactional(noRollbackFor = FencedException.class)
    public void fail(UUID attemptId, FailRequest failure) {
        Locked x = lockByAttempt(attemptId);
        if (!x.current() || !x.attempt().getStatus().live()) {
            throw new FencedException("Attempt %s is no longer current".formatted(attemptId));
        }
        if (failure.metrics() != null) {
            x.attempt().setMetrics(failure.metrics());
        }
        String error = failure.error() == null ? "Worker reported a failure" : failure.error();
        if (failure.cancelled()) {
            endLive(x, AttemptStatus.CANCELLED, EndReason.CANCELLED, error);
            if (x.job().getStatus() != JobStatus.CANCELLING && !x.job().getStatus().terminal()) {
                // Cancelled without a cancel request (e.g. the worker shut down): run it again.
                x.chunk().setStatus(ChunkStatus.PENDING);
            }
            settle(x.job());
        } else {
            failAttempt(x, failure.retryable() ? EndReason.WORKER_ERROR : EndReason.INVALID_INPUT,
                    failure.retryable(), error);
        }
        changed(x.job());
    }

    // ===================================================================================== lease monitor

    /** Attempt ids whose lease or claim deadline has passed (checked again under lock by {@link #expire}). */
    @Transactional(readOnly = true)
    public List<UUID> overdueAttempts() {
        OffsetDateTime now = now();
        List<UUID> ids = new ArrayList<>();
        attempts.findByStatusAndLeaseExpiresAtBefore(AttemptStatus.RUNNING, now).forEach(a -> ids.add(a.getId()));
        attempts.findByStatusAndClaimDeadlineBefore(AttemptStatus.DISPATCHED, now).forEach(a -> ids.add(a.getId()));
        return ids;
    }

    @Transactional
    public boolean expire(UUID attemptId) {
        Locked x = lockByAttempt(attemptId);
        OffsetDateTime now = now();
        ProcessingAttempt a = x.attempt();
        if (a.getStatus() == AttemptStatus.RUNNING && a.getLeaseExpiresAt() != null && a.getLeaseExpiresAt().isBefore(now)) {
            long silent = Duration.between(a.getLastHeartbeatAt(), now).toSeconds();
            lose(x, EndReason.LEASE_EXPIRED, true, "No heartbeat from %s for %d s".formatted(a.getWorker(), silent));
            return true;
        }
        if (a.getStatus() == AttemptStatus.DISPATCHED && a.getClaimDeadline().isBefore(now)) {
            lose(x, EndReason.CLAIM_TIMEOUT, false,
                    "No worker started the task within %d s (is the worker running?)".formatted(config.claimTimeout().toSeconds()));
            return true;
        }
        return false;
    }

    /** After an API restart, live workers get time to reconnect before their leases count as expired. */
    @Transactional
    public void applyStartupGrace() {
        OffsetDateTime until = now().plus(config.startupGrace());
        int leases = attempts.extendRunningLeases(until);
        int claims = attempts.extendClaimDeadlines(until);
        if (leases + claims > 0) {
            log.info("Startup grace: extended {} running leases and {} claim deadlines until {}", leases, claims, until);
        }
    }

    // ===================================================================================== user operations

    @Transactional
    public ProcessingJob pause(UUID jobId) {
        ProcessingJob job = lockJob(jobId);
        switch (job.getStatus()) {
            case RUNNING -> {
                job.setStatus(JobStatus.PAUSING);
                event(job, null, null, "JOB_PAUSING", JobEvent.Level.INFO,
                        "Pausing: no new work starts; running work finishes first", Map.of());
                settle(job);
            }
            case QUEUED -> {
                job.setStatus(JobStatus.PAUSED);
                event(job, null, null, "JOB_PAUSED", JobEvent.Level.INFO, "Paused before it started", Map.of());
            }
            default -> throw invalidTransition(job, "pause");
        }
        changed(job);
        return job;
    }

    @Transactional
    public ProcessingJob resume(UUID jobId) {
        ProcessingJob job = lockJob(jobId);
        switch (job.getStatus()) {
            case PAUSING -> {
                job.setStatus(JobStatus.RUNNING);
                event(job, null, null, "JOB_RESUMED", JobEvent.Level.INFO, "Resumed", Map.of());
            }
            case PAUSED -> {
                job.setStatus(JobStatus.QUEUED); // admission restarts it when a slot is free
                event(job, null, null, "JOB_RESUMED", JobEvent.Level.INFO, "Resumed; waiting for a free slot", Map.of());
            }
            default -> throw invalidTransition(job, "resume");
        }
        changed(job);
        return job;
    }

    @Transactional
    public ProcessingJob cancel(UUID jobId) {
        ProcessingJob job = lockJob(jobId);
        switch (job.getStatus()) {
            case QUEUED, PAUSED -> {
                job.setStatus(JobStatus.CANCELLING);
                settle(job); // nothing is running: becomes CANCELLED immediately
            }
            case RUNNING, PAUSING -> {
                job.setStatus(JobStatus.CANCELLING);
                event(job, null, null, "JOB_CANCELLING", JobEvent.Level.INFO,
                        "Cancelling: running work is told to stop", Map.of());
                settle(job);
            }
            default -> throw invalidTransition(job, "cancel");
        }
        changed(job);
        return job;
    }

    /** Retry a failed job: failed chunks get a fresh attempt budget; completed chunks are kept. */
    @Transactional
    public ProcessingJob retry(UUID jobId) {
        ProcessingJob job = lockJob(jobId);
        if (job.getStatus() != JobStatus.FAILED) {
            throw invalidTransition(job, "retry");
        }
        int reset = 0;
        for (ProcessingChunk c : chunks.findByJobId(jobId)) {
            if (c.getStatus() == ChunkStatus.FAILED || c.getStatus() == ChunkStatus.CANCELLED) {
                c.setStatus(ChunkStatus.PENDING);
                c.setRetryCount(0);
                c.setNotBefore(null);
                reset++;
            }
        }
        job.setStatus(JobStatus.QUEUED);
        job.setErrorMessage(null);
        job.setFinishedAt(null);
        job.incrementRetryCount();
        event(job, null, null, "JOB_RETRIED", JobEvent.Level.INFO,
                "Retrying: %d chunk(s) will run again".formatted(reset), Map.of());
        changed(job);
        return job;
    }

    // ===================================================================================== internals

    private void failAttempt(Locked x, EndReason reason, boolean retryable, String error) {
        ProcessingJob job = x.job();
        ProcessingChunk chunk = x.chunk();
        ProcessingAttempt attempt = x.attempt();
        attempt.setCountsTowardLimit(true);
        endLive(x, AttemptStatus.FAILED, reason, error);
        chunk.setRetryCount(chunk.getRetryCount() + 1);
        chunk.setErrorDetails(error);
        if (job.getStatus() == JobStatus.CANCELLING || job.getStatus().terminal()) {
            settle(job);
            return;
        }
        if (!retryable || chunk.getRetryCount() >= job.getMaxAttempts()) {
            chunk.setStatus(ChunkStatus.FAILED);
            failJob(job, "%s #%d failed%s: %s".formatted(chunk.getStage(), chunk.getChunkIndex(),
                    retryable ? " after %d attempts".formatted(chunk.getRetryCount()) : "", error));
        } else {
            chunk.setStatus(ChunkStatus.PENDING);
            chunk.setNotBefore(now().plusSeconds(5L << Math.min(chunk.getRetryCount() - 1, 4)));
            event(job, chunk, attempt, "ATTEMPT_FAILED", JobEvent.Level.WARN,
                    "%s #%d attempt %d failed; retrying (%d of %d attempts used): %s".formatted(chunk.getStage(),
                            chunk.getChunkIndex(), attempt.getAttemptNo(), chunk.getRetryCount(), job.getMaxAttempts(),
                            abbreviate(error)), Map.of());
        }
        settle(job);
    }

    /** The attempt's execution is gone (lease expired, crashed, never claimed, could not be queued). */
    private void lose(Locked x, EndReason reason, boolean counts, String message) {
        ProcessingJob job = x.job();
        ProcessingChunk chunk = x.chunk();
        x.attempt().setCountsTowardLimit(counts);
        endLive(x, AttemptStatus.LOST, reason, message);
        event(job, chunk, x.attempt(), "ATTEMPT_LOST", JobEvent.Level.WARN,
                "%s #%d attempt %d lost (%s): %s".formatted(chunk.getStage(), chunk.getChunkIndex(),
                        x.attempt().getAttemptNo(), reason, message), Map.of("reason", reason.name()));
        if (job.getStatus() == JobStatus.CANCELLING || job.getStatus().terminal()) {
            settle(job);
            return;
        }
        if (counts) {
            chunk.setRetryCount(chunk.getRetryCount() + 1);
            chunk.setErrorDetails(message);
            if (chunk.getRetryCount() >= job.getMaxAttempts()) {
                chunk.setStatus(ChunkStatus.FAILED);
                failJob(job, "%s #%d failed after %d attempts: %s".formatted(chunk.getStage(), chunk.getChunkIndex(),
                        chunk.getRetryCount(), message));
                settle(job);
                return;
            }
            chunk.setNotBefore(null);
        } else {
            chunk.setNotBefore(now().plus(config.dispatchBackoff()));
        }
        chunk.setStatus(ChunkStatus.PENDING);
        settle(job);
    }

    /** End a live attempt; the chunk leaves its live state (callers set the next chunk state). */
    private void endLive(Locked x, AttemptStatus status, EndReason reason, String error) {
        x.attempt().end(status, reason, error, now());
        if (ChunkStatus.LIVE.contains(x.chunk().getStatus())) {
            x.chunk().setStatus(status == AttemptStatus.CANCELLED ? ChunkStatus.CANCELLED : ChunkStatus.PENDING);
        }
        purgePrivate(x.job(), x.attempt().getId());
    }

    /**
     * Private outputs (speaker embeddings, voice samples) of an ended attempt are removed at once instead of waiting
     * for the scratch-folder retention. A late write by a lost worker is caught by the scheduler's private sweep.
     */
    private void purgePrivate(ProcessingJob job, UUID attemptId) {
        try {
            storage.purgeAttemptPrivate(job.getProjectId(), attemptId);
        } catch (IOException | RuntimeException e) {
            log.warn("Private outputs of attempt {} not removed yet: {}", attemptId, e.getClass().getSimpleName());
        }
    }

    private void failJob(ProcessingJob job, String message) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(abbreviate(message));
        job.setFinishedAt(now());
        // Other running attempts of this job are fenced from now on; their chunks run again on retry.
        for (ProcessingChunk c : chunks.findByJobId(job.getId())) {
            if (ChunkStatus.LIVE.contains(c.getStatus()) && c.getCurrentAttemptId() != null) {
                attempts.findById(c.getCurrentAttemptId()).filter(a -> a.getStatus().live())
                        .ifPresent(a -> {
                            a.end(AttemptStatus.CANCELLED, EndReason.JOB_FAILED, "Job failed", now());
                            purgePrivate(job, a.getId());
                        });
                c.setStatus(ChunkStatus.PENDING);
            }
        }
        event(job, null, null, "JOB_FAILED", JobEvent.Level.ERROR, job.getErrorMessage(), Map.of());
    }

    /** Re-evaluate the job after something changed: finish pausing/cancelling, or move to the next stage. */
    private void settle(ProcessingJob job) {
        boolean anyLive = chunks.findByJobId(job.getId()).stream()
                .anyMatch(c -> ChunkStatus.LIVE.contains(c.getStatus()));
        switch (job.getStatus()) {
            case PAUSING -> {
                if (!anyLive) {
                    job.setStatus(JobStatus.PAUSED);
                    event(job, null, null, "JOB_PAUSED", JobEvent.Level.INFO, "Paused", Map.of());
                }
            }
            case CANCELLING -> {
                if (!anyLive) {
                    for (ProcessingChunk c : chunks.findByJobId(job.getId())) {
                        if (!c.getStatus().done() && c.getStatus() != ChunkStatus.FAILED) {
                            c.setStatus(ChunkStatus.CANCELLED);
                        }
                    }
                    job.setStatus(JobStatus.CANCELLED);
                    job.setFinishedAt(now());
                    event(job, null, null, "JOB_CANCELLED", JobEvent.Level.INFO, "Cancelled", Map.of());
                }
            }
            case RUNNING -> advance(job);
            default -> {
            }
        }
        recomputeProgress(job);
    }

    /** If the current stage is complete, plan the next one or complete the job. */
    private void advance(ProcessingJob job) {
        if (job.getStatus() != JobStatus.RUNNING || job.getCurrentStage() == null) {
            return;
        }
        List<ProcessingChunk> stageChunks = chunks.findByJobIdAndStageOrderByChunkIndexAsc(job.getId(), job.getCurrentStage());
        if (stageChunks.isEmpty()) {
            enterStage(job, job.getCurrentStage());
            return;
        }
        if (!stageChunks.stream().allMatch(c -> c.getStatus().done())) {
            return;
        }
        List<Stage> stages = job.getType().stages();
        int next = stages.indexOf(job.getCurrentStage()) + 1;
        if (next < stages.size()) {
            enterStage(job, stages.get(next));
        } else {
            job.setStatus(JobStatus.COMPLETED);
            job.setFinishedAt(now());
            job.setProgress(100);
            planner.onJobCompleted(job);
            event(job, null, null, "JOB_COMPLETED", JobEvent.Level.INFO, "Job completed", Map.of());
        }
    }

    private void enterStage(ProcessingJob job, Stage stage) {
        job.setCurrentStage(stage);
        try {
            List<ProcessingChunk> planned = planner.plan(job, stage);
            event(job, null, null, "STAGE_STARTED", JobEvent.Level.INFO,
                    "%s: %d chunk(s)".formatted(stage, planned.size()), Map.of("chunks", planned.size()));
            if (!planned.isEmpty() && planned.stream().allMatch(c -> c.getStatus().done())) {
                advance(job); // e.g. speaker identification switched off: the stage is planned as SKIPPED
            }
        } catch (PlanningException e) {
            failJob(job, "Cannot start %s: %s".formatted(stage, e.getMessage()));
        }
    }

    private void recomputeProgress(ProcessingJob job) {
        if (job.getStatus() == JobStatus.COMPLETED) {
            job.setProgress(100);
            return;
        }
        Map<Stage, Double> weights = planner.stageWeights(job);
        Map<Stage, double[]> perStage = new HashMap<>(); // [weighted progress, total weight]
        for (ProcessingChunk c : chunks.findByJobId(job.getId())) {
            double p = c.getStatus().done() ? 100 : c.getProgress();
            double[] acc = perStage.computeIfAbsent(c.getStage(), s -> new double[2]);
            acc[0] += p * c.weight();
            acc[1] += c.weight();
        }
        double total = 0;
        for (Map.Entry<Stage, Double> w : weights.entrySet()) {
            double[] acc = perStage.get(w.getKey());
            if (acc != null && acc[1] > 0) {
                total += w.getValue() * acc[0] / acc[1];
            }
        }
        job.setProgress(total);
    }

    /** Lock the job row, then re-read it: an instance loaded earlier in this transaction could be stale. */
    private ProcessingJob lockJob(UUID jobId) {
        return refresh(jobs.lockById(jobId).orElseThrow(() -> ApiException.notFound("Job")));
    }

    private Locked lockByAttempt(UUID attemptId) {
        ProcessingAttempt attempt = attempts.findById(attemptId).orElseThrow(() -> new AttemptNotFoundException(attemptId));
        ProcessingChunk chunk = chunks.findById(attempt.getChunkId()).orElseThrow();
        ProcessingJob job = lockJob(chunk.getJobId());
        return new Locked(job, refresh(chunk), refresh(attempt));
    }

    private <T> T refresh(T entity) {
        em.refresh(entity);
        return entity;
    }

    private void event(ProcessingJob job, ProcessingChunk chunk, ProcessingAttempt attempt, String type,
                       JobEvent.Level level, String message, Map<String, Object> payload) {
        events.save(new JobEvent(job.getId(), chunk == null ? null : chunk.getId(),
                attempt == null ? null : attempt.getId(), type, level, message, payload == null ? Map.of() : payload));
    }

    private void changed(ProcessingJob job) {
        publisher.publishEvent(new JobChanged(job.getId(), job.getProjectId()));
    }

    private static ApiException invalidTransition(ProcessingJob job, String action) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_JOB_STATE",
                "Cannot %s a job that is %s.".formatted(action, job.getStatus().name().toLowerCase()));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "…";
    }
}
