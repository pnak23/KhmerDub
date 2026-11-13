package com.khmerdub.api.project;

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
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "dub_projects")
public class DubProject {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Null means "auto-detect". */
    @Column(name = "source_language", length = 20)
    private String sourceLanguage;

    @Column(name = "detected_language", length = 20)
    private String detectedLanguage;

    @Column(name = "target_language", nullable = false, length = 20)
    private String targetLanguage = "km";

    @Column(name = "translation_style", nullable = false, length = 50)
    private String translationStyle = "NATURAL";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProjectStatus status = ProjectStatus.CREATED;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "processing_profile_id")
    private UUID processingProfileId;

    @Column(name = "source_audio_stream_index")
    private Integer sourceAudioStreamIndex;

    @Column(name = "archived_at")
    private OffsetDateTime archivedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected DubProject() {
    }

    public DubProject(String name) {
        this.name = name;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceLanguage() { return sourceLanguage; }
    public void setSourceLanguage(String sourceLanguage) { this.sourceLanguage = sourceLanguage; }
    public String getDetectedLanguage() { return detectedLanguage; }
    public void setDetectedLanguage(String detectedLanguage) { this.detectedLanguage = detectedLanguage; }
    public String getTargetLanguage() { return targetLanguage; }
    public String getTranslationStyle() { return translationStyle; }
    public void setTranslationStyle(String translationStyle) { this.translationStyle = translationStyle; }
    public ProjectStatus getStatus() { return status; }
    public void setStatus(ProjectStatus status) { this.status = status; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public UUID getProcessingProfileId() { return processingProfileId; }
    public void setProcessingProfileId(UUID processingProfileId) { this.processingProfileId = processingProfileId; }
    public Integer getSourceAudioStreamIndex() { return sourceAudioStreamIndex; }
    public void setSourceAudioStreamIndex(Integer index) { this.sourceAudioStreamIndex = index; }
    public OffsetDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(OffsetDateTime archivedAt) { this.archivedAt = archivedAt; }
    public boolean isArchived() { return archivedAt != null; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
