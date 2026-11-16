package com.khmerdub.api.dialogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One dialogue line. AI output and user edits live in separate columns: source_text (speech recognition) is never
 * changed by the user (source_text_edited is), translated_text is AI-only and edited_text is the user's Khmer.
 */
@Entity
@Table(name = "dialogue_segments")
public class DialogueSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "speaker_id")
    private UUID speakerId;

    @Column(name = "sequence_no", nullable = false)
    private int sequenceNo;

    @Column(name = "start_ms", nullable = false)
    private long startMs;

    @Column(name = "end_ms", nullable = false)
    private long endMs;

    @Column(name = "source_language", length = 20)
    private String sourceLanguage;

    @Column(name = "source_text", nullable = false)
    private String sourceText;

    @Column(name = "source_text_edited")
    private String sourceTextEdited;

    @Column(name = "translated_text")
    private String translatedText;

    @Column(name = "edited_text")
    private String editedText;

    private Float confidence;

    @Column(name = "review_status", nullable = false, length = 20)
    private String reviewStatus = "PENDING";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Map<String, Object>> words = new ArrayList<>();

    @Column(name = "speaker_assignment", nullable = false, length = 10)
    private String speakerAssignment = "AUTO";

    @Column(name = "speaker_purity")
    private Float speakerPurity;

    @Column(name = "asr_avg_logprob")
    private Float asrAvgLogprob;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> flags = new HashMap<>();

    @Version
    @Column(nullable = false)
    private long version;

    protected DialogueSegment() {
    }

    public DialogueSegment(UUID projectId, UUID revisionId, UUID speakerId, int sequenceNo, long startMs, long endMs,
                           String sourceLanguage, String sourceText, Float confidence,
                           List<Map<String, Object>> words, Float speakerPurity, Float asrAvgLogprob,
                           Map<String, Object> flags) {
        this.projectId = projectId;
        this.revisionId = revisionId;
        this.speakerId = speakerId;
        this.sequenceNo = sequenceNo;
        this.startMs = startMs;
        this.endMs = endMs;
        this.sourceLanguage = sourceLanguage;
        this.sourceText = sourceText;
        this.confidence = confidence;
        this.words = new ArrayList<>(words);
        this.speakerPurity = speakerPurity;
        this.asrAvgLogprob = asrAvgLogprob;
        this.flags = new HashMap<>(flags);
        this.reviewStatus = flags.isEmpty() ? "PENDING" : "NEEDS_REVIEW";
    }

    public Map<String, Object> export() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("speakerId", speakerId);
        m.put("sequenceNo", sequenceNo);
        m.put("startMs", startMs);
        m.put("endMs", endMs);
        m.put("sourceLanguage", sourceLanguage);
        m.put("sourceText", sourceText);
        m.put("sourceTextEdited", sourceTextEdited);
        m.put("translatedText", translatedText);
        m.put("editedText", editedText);
        m.put("confidence", confidence);
        m.put("reviewStatus", reviewStatus);
        m.put("words", words);
        m.put("speakerAssignment", speakerAssignment);
        m.put("speakerPurity", speakerPurity);
        m.put("asrAvgLogprob", asrAvgLogprob);
        m.put("flags", flags);
        return m;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getRevisionId() { return revisionId; }
    public UUID getSpeakerId() { return speakerId; }
    public int getSequenceNo() { return sequenceNo; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getSourceLanguage() { return sourceLanguage; }
    public String getSourceText() { return sourceText; }
    public String getSourceTextEdited() { return sourceTextEdited; }
    public String getTranslatedText() { return translatedText; }
    public String getEditedText() { return editedText; }
    public Float getConfidence() { return confidence; }
    public String getReviewStatus() { return reviewStatus; }
    public List<Map<String, Object>> getWords() { return words; }
    public String getSpeakerAssignment() { return speakerAssignment; }
    public Float getSpeakerPurity() { return speakerPurity; }
    public Float getAsrAvgLogprob() { return asrAvgLogprob; }
    public Map<String, Object> getFlags() { return flags; }
    public long getVersion() { return version; }
}
