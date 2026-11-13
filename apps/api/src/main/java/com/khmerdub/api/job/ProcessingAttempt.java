package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEnums.AttemptStatus;
import com.khmerdub.api.job.JobEnums.EndReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

/**
 * One execution of a chunk. The id is the fencing token: it is the Celery task id, it appears in every worker
 * callback and in every output path, and only the chunk's current attempt may publish results.
 */
@Entity
@Table(name = "processing_attempts")
public class ProcessingAttempt {

    @Id
    private UUID id;

    @Column(name = "chunk_id", nullable = false)
    private UUID chunkId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttemptStatus status = AttemptStatus.DISPATCHED;

    @Column(name = "counts_toward_limit", nullable = false)
    private boolean countsTowardLimit = true;

    @Column(length = 200)
    private String worker;

    @Column(name = "dispatched_at", nullable = false)
    private OffsetDateTime dispatchedAt;

    @Column(name = "claim_deadline", nullable = false)
    private OffsetDateTime claimDeadline;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @Column(name = "last_heartbeat_at")
    private OffsetDateTime lastHeartbeatAt;

    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal progress = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason", length = 40)
    private EndReason endReason;

    @Column(name = "error_details")
    private String errorDetails;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> metrics = new HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProcessingAttempt() {
    }

    public ProcessingAttempt(UUID id, UUID chunkId, int attemptNo, OffsetDateTime now, OffsetDateTime claimDeadline) {
        this.id = id;
        this.chunkId = chunkId;
        this.attemptNo = attemptNo;
        this.dispatchedAt = now;
        this.claimDeadline = claimDeadline;
    }

    /** Ends the attempt. Callers must have checked that it is still live. */
    public void end(AttemptStatus finalStatus, EndReason reason, String error, OffsetDateTime now) {
        this.status = finalStatus;
        this.endReason = reason;
        this.errorDetails = error;
        this.finishedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getChunkId() { return chunkId; }
    public int getAttemptNo() { return attemptNo; }
    public AttemptStatus getStatus() { return status; }
    public void setStatus(AttemptStatus status) { this.status = status; }
    public boolean isCountsTowardLimit() { return countsTowardLimit; }
    public void setCountsTowardLimit(boolean counts) { this.countsTowardLimit = counts; }
    public String getWorker() { return worker; }
    public void setWorker(String worker) { this.worker = worker; }
    public OffsetDateTime getDispatchedAt() { return dispatchedAt; }
    public OffsetDateTime getClaimDeadline() { return claimDeadline; }
    public void setClaimDeadline(OffsetDateTime claimDeadline) { this.claimDeadline = claimDeadline; }
    public OffsetDateTime getClaimedAt() { return claimedAt; }
    public void setClaimedAt(OffsetDateTime claimedAt) { this.claimedAt = claimedAt; }
    public OffsetDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(OffsetDateTime at) { this.lastHeartbeatAt = at; }
    public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }
    public OffsetDateTime getFinishedAt() { return finishedAt; }
    public double getProgress() { return progress.doubleValue(); }
    public void setProgress(double value) {
        this.progress = BigDecimal.valueOf(Math.max(0, Math.min(100, value))).setScale(2, RoundingMode.HALF_UP);
    }
    public EndReason getEndReason() { return endReason; }
    public String getErrorDetails() { return errorDetails; }
    public Map<String, Object> getMetrics() { return metrics; }
    public void setMetrics(Map<String, Object> metrics) { this.metrics = metrics; }
}
