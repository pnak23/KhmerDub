package com.khmerdub.api.power;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The keep-awake decision as a pure function (unit-tested at every boundary).
 *
 * <p>Definitions (lease = worker lease, 60 s by default):
 * <ul>
 *   <li><b>worker signal</b>: the latest claim, heartbeat, or worker-reported result (COMPLETED / WORKER_ERROR /
 *       INVALID_INPUT) of any attempt of the job. Engine-side events (re-dispatch, LOST, claim timeout) are NOT
 *       signals, so a dead worker cannot keep the PC awake through retries.</li>
 *   <li><b>fresh</b>: now - max(worker signal, run start) &lt;= lease + recoveryMargin (60 s + 120 s = 180 s).
 *       I.e. the worker stopped heart-beating, its lease expired (60 s) and no worker picked the work up again
 *       within the recovery margin.</li>
 *   <li><b>progressing</b>: now - max(progress change, chunk completion, run start) &lt;= stallLimit (30 min).</li>
 * </ul>
 * Rules: RUNNING holds while fresh AND progressing AND the run is younger than maxHold (24 h). PAUSING holds while
 * a live attempt is fresh AND the pause began at most pausingMax (20 min) ago. CANCELLING holds while a live attempt
 * exists AND the cancellation began at most cancellingMax (3 min) ago. Every other status never holds.
 */
public final class KeepAwakePolicy {

    private KeepAwakePolicy() {
    }

    /** What the engine knows about one heavy job that is RUNNING, PAUSING or CANCELLING. */
    public record JobSnapshot(UUID jobId, String status, OffsetDateTime runStartedAt, OffsetDateTime statusSince,
                              OffsetDateTime lastWorkerSignal, OffsetDateTime lastProgress, int liveAttempts) {
    }

    public record Decision(boolean hold, UUID jobId, String reason) {
        static Decision release(String reason) {
            return new Decision(false, null, reason);
        }
    }

    public static Decision decide(OffsetDateTime now, List<JobSnapshot> jobs, Duration lease, PowerProperties p) {
        Duration freshFor = lease.plus(p.recoveryMargin());
        String why = "no heavy job is processing";
        for (JobSnapshot j : jobs) {
            OffsetDateTime start = j.runStartedAt() == null ? now : j.runStartedAt();
            OffsetDateTime signal = latest(j.lastWorkerSignal(), start);
            boolean fresh = !signal.plus(freshFor).isBefore(now);
            switch (j.status()) {
                case "RUNNING" -> {
                    OffsetDateTime progress = latest(j.lastProgress(), start);
                    if (!fresh) {
                        why = "job %s: no worker signal for %d s (lease %d s + recovery %d s)".formatted(j.jobId(),
                                Duration.between(signal, now).toSeconds(), lease.toSeconds(), p.recoveryMargin().toSeconds());
                    } else if (progress.plus(p.stallLimit()).isBefore(now)) {
                        why = "job %s: no progress for %d min".formatted(j.jobId(), Duration.between(progress, now).toMinutes());
                    } else if (start.plus(p.maxHold()).isBefore(now)) {
                        why = "job %s: running longer than the %d h limit".formatted(j.jobId(), p.maxHold().toHours());
                    } else {
                        return new Decision(true, j.jobId(), "job %s RUNNING".formatted(j.jobId()));
                    }
                }
                case "PAUSING" -> {
                    OffsetDateTime since = Optional.ofNullable(j.statusSince()).orElse(now);
                    if (j.liveAttempts() > 0 && fresh && !since.plus(p.pausingMax()).isBefore(now)) {
                        return new Decision(true, j.jobId(), "job %s PAUSING (running chunk finishing)".formatted(j.jobId()));
                    }
                    why = "job %s PAUSING: limit reached or worker silent".formatted(j.jobId());
                }
                case "CANCELLING" -> {
                    OffsetDateTime since = Optional.ofNullable(j.statusSince()).orElse(now);
                    if (j.liveAttempts() > 0 && !since.plus(p.cancellingMax()).isBefore(now)) {
                        return new Decision(true, j.jobId(), "job %s CANCELLING".formatted(j.jobId()));
                    }
                    why = "job %s CANCELLING: limit reached".formatted(j.jobId());
                }
                default -> {
                }
            }
        }
        return Decision.release(why);
    }

    private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) {
            return b;
        }
        return a.isAfter(b) ? a : b;
    }
}
