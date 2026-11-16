package com.khmerdub.api.dialogue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** A character (one voice) within a dialogue revision. Holds no biometric data. */
@Entity
@Table(name = "speakers")
public class Speaker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "speaker_code", nullable = false, length = 50)
    private String speakerCode;

    @Column(name = "character_name", length = 150)
    private String characterName;

    private String description;

    @Column(name = "suggested_category", length = 30)
    private String suggestedCategory;

    @Column(name = "voice_profile_id")
    private UUID voiceProfileId;

    @Column(name = "voice_assignment", nullable = false, length = 20)
    private String voiceAssignment = "NONE";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "voice_settings", nullable = false)
    private Map<String, Object> voiceSettings = new HashMap<>();

    @Column(name = "review_status", nullable = false, length = 20)
    private String reviewStatus = "UNREVIEWED";

    @Column(nullable = false, length = 20)
    private String origin = "DIARIZATION";

    @Column(name = "merged_into_id")
    private UUID mergedIntoId;

    @Column(name = "speech_ms", nullable = false)
    private long speechMs;

    @Column(name = "segment_count", nullable = false)
    private int segmentCount;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected Speaker() {
    }

    public Speaker(UUID projectId, UUID revisionId, String speakerCode, long speechMs, int segmentCount,
                   int displayOrder) {
        this.projectId = projectId;
        this.revisionId = revisionId;
        this.speakerCode = speakerCode;
        this.speechMs = speechMs;
        this.segmentCount = segmentCount;
        this.displayOrder = displayOrder;
    }

    /** Copy for a backup/restore round trip (all user-visible fields). */
    public Map<String, Object> export() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("speakerCode", speakerCode);
        m.put("characterName", characterName);
        m.put("description", description);
        m.put("suggestedCategory", suggestedCategory);
        m.put("voiceProfileId", voiceProfileId);
        m.put("voiceAssignment", voiceAssignment);
        m.put("voiceSettings", voiceSettings);
        m.put("reviewStatus", reviewStatus);
        m.put("origin", origin);
        m.put("mergedIntoId", mergedIntoId);
        m.put("speechMs", speechMs);
        m.put("segmentCount", segmentCount);
        m.put("displayOrder", displayOrder);
        return m;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getRevisionId() { return revisionId; }
    public String getSpeakerCode() { return speakerCode; }
    public String getCharacterName() { return characterName; }
    public void setCharacterName(String characterName) { this.characterName = characterName; }
    public String getDescription() { return description; }
    public String getSuggestedCategory() { return suggestedCategory; }
    public UUID getVoiceProfileId() { return voiceProfileId; }
    public String getVoiceAssignment() { return voiceAssignment; }
    public String getReviewStatus() { return reviewStatus; }
    public String getOrigin() { return origin; }
    public UUID getMergedIntoId() { return mergedIntoId; }
    public long getSpeechMs() { return speechMs; }
    public int getSegmentCount() { return segmentCount; }
    public int getDisplayOrder() { return displayOrder; }
    public long getVersion() { return version; }
}
