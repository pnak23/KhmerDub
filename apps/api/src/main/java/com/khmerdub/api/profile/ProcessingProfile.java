package com.khmerdub.api.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Read-only view of a processing profile (only the fields the engine uses so far). */
@Entity
@Table(name = "processing_profiles")
public class ProcessingProfile {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    private String description;

    @Column(name = "is_default", nullable = false)
    private boolean defaultProfile;

    @Column(name = "max_duration_ms", nullable = false)
    private long maxDurationMs;

    @Column(name = "extraction_segment_ms", nullable = false)
    private long extractionSegmentMs;

    @Column(name = "transcription_chunk_ms", nullable = false)
    private long transcriptionChunkMs;

    protected ProcessingProfile() {
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isDefaultProfile() { return defaultProfile; }
    public long getMaxDurationMs() { return maxDurationMs; }
    public long getExtractionSegmentMs() { return extractionSegmentMs; }
    public long getTranscriptionChunkMs() { return transcriptionChunkMs; }
}
