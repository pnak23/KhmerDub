package com.khmerdub.api.job;

import com.khmerdub.api.config.KhmerDubProperties;
import com.khmerdub.api.job.JobEngine.DispatchOrder;
import com.khmerdub.api.job.JobEnums.AttemptStatus;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Drives the engine: admits jobs and dispatches work (every second), expires leases (every 5 s), and removes
 * old attempt scratch folders (hourly). Disabled in tests, which call the engine directly.
 */
@Component
public class EngineScheduler {

    private static final Logger log = LoggerFactory.getLogger(EngineScheduler.class);

    private final JobEngine engine;
    private final AttemptRepository attempts;
    private final ChunkRepository chunks;
    private final JobRepository jobs;
    private final StorageService storage;
    private final RestClient aiService;
    private final KhmerDubProperties.Jobs config;
    private final Clock clock;
    private final AtomicBoolean graceApplied = new AtomicBoolean(false);

    public EngineScheduler(JobEngine engine, AttemptRepository attempts, ChunkRepository chunks, JobRepository jobs,
                           StorageService storage, RestClient aiServiceRestClient, KhmerDubProperties props,
                           Clock clock) {
        this.engine = engine;
        this.attempts = attempts;
        this.chunks = chunks;
        this.jobs = jobs;
        this.storage = storage;
        this.aiService = aiServiceRestClient;
        this.config = props.jobs();
        this.clock = clock;
    }

    @Scheduled(initialDelay = 3000, fixedDelay = 1000)
    public void dispatchTick() {
        if (!config.schedulerEnabled() || !graceApplied.get()) {
            return;
        }
        try {
            engine.admitQueued();
            for (DispatchOrder order : engine.reserveDispatches("cpu", config.cpuSlots())) {
                send(order);
            }
        } catch (RuntimeException e) {
            log.warn("Dispatch tick failed: {}", e.toString());
        }
    }

    void send(DispatchOrder order) {
        try {
            aiService.post().uri("/internal/tasks/dispatch")
                    .body(Map.of("attemptId", order.attemptId(), "stage", order.stage(), "queue", order.queue()))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            engine.dispatchFailed(order.attemptId(), e.getClass().getSimpleName());
        }
    }

    @Scheduled(initialDelay = 2000, fixedDelay = 5000)
    public void leaseTick() {
        if (!config.schedulerEnabled()) {
            return;
        }
        if (graceApplied.compareAndSet(false, true)) {
            engine.applyStartupGrace(); // first tick after start: give live workers time to reconnect
            return;
        }
        for (UUID attemptId : engine.overdueAttempts()) {
            try {
                engine.expire(attemptId);
            } catch (RuntimeException e) {
                log.warn("Could not expire attempt {}: {}", attemptId, e.toString());
            }
        }
    }

    /** Remove scratch folders of attempts that ended more than work-retention ago. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void janitorTick() {
        if (!config.schedulerEnabled()) {
            return;
        }
        OffsetDateTime before = OffsetDateTime.now(clock).minus(config.workRetention());
        int removed = 0;
        for (ProcessingAttempt a : attempts.findFinishedBefore(AttemptStatus.LIVE, before)) {
            UUID projectId = chunks.findById(a.getChunkId()).flatMap(c -> jobs.findById(c.getJobId()))
                    .map(ProcessingJob::getProjectId).orElse(null);
            if (projectId == null) {
                continue;
            }
            try {
                if (storage.deleteAttemptWorkDir(projectId, a.getId())) {
                    removed++;
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Scratch folder of attempt {} not removed yet: {}", a.getId(), e.toString());
            }
        }
        if (removed > 0) {
            log.info("Janitor removed {} attempt scratch folder(s)", removed);
        }
    }
}
