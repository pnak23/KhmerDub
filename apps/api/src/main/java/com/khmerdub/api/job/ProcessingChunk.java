package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEnums.ChunkStatus;
import com.khmerdub.api.job.JobEnums.Stage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** One independently retryable unit of a stage. Times are milliseconds relative to the original movie. */
@Entity
@Table(name = "processing_chunks")
public class ProcessingChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Stage stage;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "start_ms")
    private Long startMs;

    @Column(name = "end_ms")
    private Long endMs;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ChunkStatus status = ChunkStatus.PENDING;

    /** Failed attempts that count toward the job's attempt limit. */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "input_hash", length = 64)
    private String inputHash;

    @Column(name = "result_asset_id")
    private UUID resultAssetId;

    @Column(name = "error_details")
    private String errorDetails;

    @Column(name = "current_attempt_id")
    private UUID currentAttemptId;

    @Column(nullable = false, length = 20)
    private String queue = "cpu";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> params = new HashMap<>();

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal progress = BigDecimal.ZERO;

    @Column(name = "not_before")
    private OffsetDateTime notBefore;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProcessingChunk() {
    }

    public ProcessingChunk(UUID jobId, Stage stage, int chunkIndex, Long startMs, Long endMs, String queue,
                           Map<String, Object> params) {
        this.jobId = jobId;
        this.stage = stage;
        this.chunkIndex = chunkIndex;
        this.startMs = startMs;
        this.endMs = endMs;
        this.queue = queue;
        this.params = new HashMap<>(params);
    }

    /** Relative weight for progress: the time span, or 1 for non-time chunks. */
    public long weight() {
        return startMs == null ? 1 : Math.max(1, endMs - startMs);
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public Stage getStage() { return stage; }
    public int getChunkIndex() { return chunkIndex; }
    public Long getStartMs() { return startMs; }
    public Long getEndMs() { return endMs; }
    public ChunkStatus getStatus() { return status; }
    public void setStatus(ChunkStatus status) { this.status = status; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public UUID getResultAssetId() { return resultAssetId; }
    public void setResultAssetId(UUID resultAssetId) { this.resultAssetId = resultAssetId; }
    public String getErrorDetails() { return errorDetails; }
    public void setErrorDetails(String errorDetails) { this.errorDetails = errorDetails; }
    public UUID getCurrentAttemptId() { return currentAttemptId; }
    public void setCurrentAttemptId(UUID currentAttemptId) { this.currentAttemptId = currentAttemptId; }
    public String getQueue() { return queue; }
    public Map<String, Object> getParams() { return params; }
    /** Replace the params (a new map, so the JSON column is reliably marked dirty). */
    public void setParams(Map<String, Object> params) { this.params = new HashMap<>(params); }
    public double getProgress() { return progress.doubleValue(); }
    public void setProgress(double value) {
        this.progress = BigDecimal.valueOf(Math.max(0, Math.min(100, value))).setScale(2, RoundingMode.HALF_UP);
    }
    public OffsetDateTime getNotBefore() { return notBefore; }
    public void setNotBefore(OffsetDateTime notBefore) { this.notBefore = notBefore; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
    public long getVersion() { return version; }
}
