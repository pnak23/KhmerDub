package com.khmerdub.api.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
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

    @Column(name = "transcription_overlap_ms", nullable = false)
    private long transcriptionOverlapMs;

    @Column(name = "diarization_enabled", nullable = false)
    private boolean diarizationEnabled;

    // M3a-verified speech settings (V3)
    @Column(name = "whisper_beam_size", nullable = false)
    private int whisperBeamSize;

    @Column(name = "whisper_cpu_threads", nullable = false)
    private int whisperCpuThreads;

    @Column(name = "diarization_threads", nullable = false)
    private int diarizationThreads;

    @Column(name = "diarization_mode", nullable = false, length = 10)
    private String diarizationMode;

    @Column(name = "diarization_step_quality", nullable = false)
    private BigDecimal diarizationStepQuality;

    @Column(name = "diarization_step_fast", nullable = false)
    private BigDecimal diarizationStepFast;

    @Column(name = "diarization_chunk_ms", nullable = false)
    private long diarizationChunkMs;

    @Column(name = "diarization_overlap_ms", nullable = false)
    private long diarizationOverlapMs;

    @Column(name = "speaker_match_threshold", nullable = false)
    private BigDecimal speakerMatchThreshold;

    protected ProcessingProfile() {
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isDefaultProfile() { return defaultProfile; }
    public long getMaxDurationMs() { return maxDurationMs; }
    public long getExtractionSegmentMs() { return extractionSegmentMs; }
    public long getTranscriptionChunkMs() { return transcriptionChunkMs; }
    public long getTranscriptionOverlapMs() { return transcriptionOverlapMs; }
    public boolean isDiarizationEnabled() { return diarizationEnabled; }
    public int getWhisperBeamSize() { return whisperBeamSize; }
    public int getWhisperCpuThreads() { return whisperCpuThreads; }
    public int getDiarizationThreads() { return diarizationThreads; }
    public String getDiarizationMode() { return diarizationMode; }
    public double getDiarizationStepQuality() { return diarizationStepQuality.doubleValue(); }
    public double getDiarizationStepFast() { return diarizationStepFast.doubleValue(); }
    public long getDiarizationChunkMs() { return diarizationChunkMs; }
    public long getDiarizationOverlapMs() { return diarizationOverlapMs; }
    public double getSpeakerMatchThreshold() { return speakerMatchThreshold.doubleValue(); }
}
