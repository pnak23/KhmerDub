package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEnums.AttemptStatus;
import com.khmerdub.api.job.JobEnums.ChunkStatus;
import com.khmerdub.api.job.JobEnums.EndReason;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobEnums.Stage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read models sent to the UI (REST and WebSocket). */
public final class JobViews {

    private JobViews() {
    }

    public record JobBrief(UUID id, JobType type, JobStatus status, Stage currentStage, double progress,
                           String errorMessage, OffsetDateTime createdAt, OffsetDateTime updatedAt) {

        public static JobBrief of(ProcessingJob j) {
            return new JobBrief(j.getId(), j.getType(), j.getStatus(), j.getCurrentStage(), j.getProgress(),
                    j.getErrorMessage(), j.getCreatedAt(), j.getUpdatedAt());
        }
    }

    public record AttemptView(UUID id, int attemptNo, AttemptStatus status, boolean countsTowardLimit, String worker,
                              OffsetDateTime dispatchedAt, OffsetDateTime claimedAt, OffsetDateTime finishedAt,
                              EndReason endReason, String error, Map<String, Object> metrics) {

        public static AttemptView of(ProcessingAttempt a) {
            return new AttemptView(a.getId(), a.getAttemptNo(), a.getStatus(), a.isCountsTowardLimit(), a.getWorker(),
                    a.getDispatchedAt(), a.getClaimedAt(), a.getFinishedAt(), a.getEndReason(), a.getErrorDetails(),
                    a.getMetrics());
        }
    }

    public record ChunkView(UUID id, Stage stage, int index, Long startMs, Long endMs, ChunkStatus status,
                            double progress, int retryCount, UUID currentAttemptId, UUID resultAssetId, String error,
                            List<AttemptView> attempts) {
    }

    /** {@code version} increases with every change; clients ignore snapshots older than what they have. */
    public record JobView(UUID id, UUID projectId, JobType type, JobStatus status, Stage currentStage,
                          List<Stage> stages, double progress, int maxAttempts, int retryCount, String errorMessage,
                          UUID resultAssetId, Map<String, Object> parameters, OffsetDateTime createdAt,
                          OffsetDateTime startedAt, OffsetDateTime finishedAt, OffsetDateTime updatedAt, long version,
                          List<ChunkView> chunks) {
    }

    public record EventView(long seq, UUID chunkId, UUID attemptId, String type, String level, String message,
                            Map<String, Object> payload, OffsetDateTime createdAt) {

        public static EventView of(JobEvent e) {
            return new EventView(e.getSeq(), e.getChunkId(), e.getAttemptId(), e.getEventType(), e.getLevel(),
                    e.getMessage(), e.getPayload(), e.getCreatedAt());
        }
    }
}
