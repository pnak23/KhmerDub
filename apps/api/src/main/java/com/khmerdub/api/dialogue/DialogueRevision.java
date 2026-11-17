package com.khmerdub.api.dialogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One complete result of a TRANSCRIBE run. Exactly one revision per project is ACTIVE; a new run never touches it.
 * READY waits for the user to activate it; SUPERSEDED revisions are kept read-only and can be activated again.
 */
@Entity
@Table(name = "dialogue_revisions")
public class DialogueRevision {

    public enum Status { BUILDING, READY, ACTIVE, SUPERSEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "revision_no", nullable = false)
    private int revisionNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.BUILDING;

    @Column(name = "source_job_id")
    private UUID sourceJobId;

    @Column(name = "diarization_mode", length = 10)
    private String diarizationMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> parameters = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> summary = new HashMap<>();

    @Column(name = "backup_asset_id")
    private UUID backupAssetId;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "superseded_at")
    private OffsetDateTime supersededAt;

    protected DialogueRevision() {
    }

    public DialogueRevision(UUID projectId, int revisionNo, UUID sourceJobId, String diarizationMode,
                            Map<String, Object> parameters) {
        this.projectId = projectId;
        this.revisionNo = revisionNo;
        this.sourceJobId = sourceJobId;
        this.diarizationMode = diarizationMode;
        this.parameters = new HashMap<>(parameters);
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public int getRevisionNo() { return revisionNo; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public UUID getSourceJobId() { return sourceJobId; }
    public String getDiarizationMode() { return diarizationMode; }
    public Map<String, Object> getParameters() { return parameters; }
    public Map<String, Object> getSummary() { return summary; }
    public void setSummary(Map<String, Object> summary) { this.summary = new HashMap<>(summary); }
    public UUID getBackupAssetId() { return backupAssetId; }
    public void setBackupAssetId(UUID backupAssetId) { this.backupAssetId = backupAssetId; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(OffsetDateTime at) { this.activatedAt = at; }
    public OffsetDateTime getSupersededAt() { return supersededAt; }
    public void setSupersededAt(OffsetDateTime at) { this.supersededAt = at; }
}
