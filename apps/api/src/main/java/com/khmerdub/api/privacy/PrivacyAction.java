package com.khmerdub.api.privacy;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Evidence of a privacy deletion: counts only, never contents or file names. */
@Entity
@Table(name = "privacy_actions")
public class PrivacyAction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(name = "files_deleted", nullable = false)
    private int filesDeleted;

    @Column(name = "bytes_deleted", nullable = false)
    private long bytesDeleted;

    @Column(nullable = false)
    private boolean verified;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> details = new HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected PrivacyAction() {
    }

    public PrivacyAction(UUID projectId, String action, int filesDeleted, long bytesDeleted, boolean verified,
                         Map<String, Object> details) {
        this.projectId = projectId;
        this.action = action;
        this.filesDeleted = filesDeleted;
        this.bytesDeleted = bytesDeleted;
        this.verified = verified;
        this.details = new HashMap<>(details);
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getAction() { return action; }
    public int getFilesDeleted() { return filesDeleted; }
    public long getBytesDeleted() { return bytesDeleted; }
    public boolean isVerified() { return verified; }
    public Map<String, Object> getDetails() { return details; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
