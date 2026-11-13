package com.khmerdub.api.job;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only history of a job: state changes, retries, errors, measurements. */
@Entity
@Table(name = "job_events")
public class JobEvent {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "chunk_id")
    private UUID chunkId;

    @Column(name = "attempt_id")
    private UUID attemptId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, length = 10)
    private String level;

    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload = new HashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Generated
    @Column(insertable = false, updatable = false)
    private Long seq;

    protected JobEvent() {
    }

    public JobEvent(UUID jobId, UUID chunkId, UUID attemptId, String eventType, Level level, String message,
                    Map<String, Object> payload) {
        this.jobId = jobId;
        this.chunkId = chunkId;
        this.attemptId = attemptId;
        this.eventType = eventType;
        this.level = level.name();
        this.message = message;
        this.payload = new HashMap<>(payload);
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public UUID getChunkId() { return chunkId; }
    public UUID getAttemptId() { return attemptId; }
    public String getEventType() { return eventType; }
    public String getLevel() { return level; }
    public String getMessage() { return message; }
    public Map<String, Object> getPayload() { return payload; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public Long getSeq() { return seq; }
}
