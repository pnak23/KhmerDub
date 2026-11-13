package com.khmerdub.api.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** A file in the storage root. {@code storagePath} is relative to the root and uses forward slashes. */
@Entity
@Table(name = "media_assets")
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 40)
    private MediaAssetType type;

    @Column(nullable = false, length = 500)
    private String filename;

    @Column(name = "storage_path", nullable = false, length = 1000)
    private String storagePath;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 64)
    private String sha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "produced_by_attempt_id")
    private UUID producedByAttemptId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected MediaAsset() {
    }

    public MediaAsset(UUID projectId, MediaAssetType type, String filename, String storagePath, String mimeType,
                      long fileSizeBytes, String sha256, UUID producedByAttemptId) {
        this.projectId = projectId;
        this.type = type;
        this.filename = filename;
        this.storagePath = storagePath;
        this.mimeType = mimeType;
        this.fileSizeBytes = fileSizeBytes;
        this.sha256 = sha256;
        this.producedByAttemptId = producedByAttemptId;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public MediaAssetType getType() { return type; }
    public String getFilename() { return filename; }
    public String getStoragePath() { return storagePath; }
    public String getMimeType() { return mimeType; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public String getSha256() { return sha256; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
    public UUID getProducedByAttemptId() { return producedByAttemptId; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
