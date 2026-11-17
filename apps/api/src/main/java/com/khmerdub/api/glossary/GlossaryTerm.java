package com.khmerdub.api.glossary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A project glossary entry: the canonical spelling of a name/place/term, the spellings speech recognition got wrong
 * (variants, e.g. 碎碎 for 岁岁), and the Khmer term to use later. Revision-independent.
 */
@Entity
@Table(name = "glossary_terms")
public class GlossaryTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 20)
    private String category = "CHARACTER";

    @Column(name = "source_term", nullable = false, length = 200)
    private String sourceTerm;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> variants = new ArrayList<>();

    @Column(name = "khmer_term", length = 200)
    private String khmerTerm;

    private String notes;

    @Column(name = "speaker_code", length = 50)
    private String speakerCode;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected GlossaryTerm() {
    }

    public GlossaryTerm(UUID projectId) {
        this.projectId = projectId;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSourceTerm() { return sourceTerm; }
    public void setSourceTerm(String sourceTerm) { this.sourceTerm = sourceTerm; }
    public List<String> getVariants() { return variants; }
    public void setVariants(List<String> variants) { this.variants = new ArrayList<>(variants); }
    public String getKhmerTerm() { return khmerTerm; }
    public void setKhmerTerm(String khmerTerm) { this.khmerTerm = khmerTerm; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getSpeakerCode() { return speakerCode; }
    public void setSpeakerCode(String speakerCode) { this.speakerCode = speakerCode; }
    public long getVersion() { return version; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
