package com.khmerdub.api.glossary;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class GlossaryViews {

    private GlossaryViews() {
    }

    public record CreateTermRequest(
            @NotBlank @Size(max = 200) String sourceTerm,
            @Pattern(regexp = "CHARACTER|PLACE|TERM") String category,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> variants,
            @Size(max = 200) String khmerTerm,
            @Size(max = 2_000) String notes,
            @Size(max = 50) String speakerCode) {
    }

    /** null = unchanged; "" clears khmerTerm / notes / speakerCode. */
    public record UpdateTermRequest(
            @NotNull Long version,
            @Size(min = 1, max = 200) String sourceTerm,
            @Pattern(regexp = "CHARACTER|PLACE|TERM") String category,
            @Size(max = 50) List<@NotBlank @Size(max = 200) String> variants,
            @Size(max = 200) String khmerTerm,
            @Size(max = 2_000) String notes,
            @Size(max = 50) String speakerCode) {
    }

    public record TermView(UUID id, String category, String sourceTerm, List<String> variants, String khmerTerm,
                           String notes, String speakerCode, long version, OffsetDateTime createdAt,
                           OffsetDateTime updatedAt) {

        static TermView of(GlossaryTerm t) {
            return new TermView(t.getId(), t.getCategory(), t.getSourceTerm(), t.getVariants(), t.getKhmerTerm(),
                    t.getNotes(), t.getSpeakerCode(), t.getVersion(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    /** termIds null = all terms of the project. */
    public record PreviewRequest(@Size(max = 1_000) List<UUID> termIds) {
    }

    public record Match(String variant, String term, int count) {
    }

    /** One line that would change: its Chinese now (your correction, or the recognised text) and after. */
    public record PreviewLine(UUID segmentId, int seq, long version, String before, String after, List<Match> matches) {
    }

    /** token proves the apply is based on exactly this preview (same lines, versions, terms). */
    public record PreviewResult(UUID revisionId, String token, int lines, int replacements, List<PreviewLine> changes) {
    }

    /** segmentIds null = every previewed line; otherwise a subset of the preview. */
    public record ApplyRequest(@Size(max = 1_000) List<UUID> termIds, @NotBlank @Size(max = 100) String token,
                               @Size(max = 50_000) List<UUID> segmentIds) {
    }
}
