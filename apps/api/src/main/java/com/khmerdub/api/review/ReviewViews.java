package com.khmerdub.api.review;

import com.khmerdub.api.dialogue.DialogueViews.LineView;
import com.khmerdub.api.dialogue.DialogueViews.SpeakerView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Request and response shapes of the review API. Text fields follow one rule: null = leave unchanged, "" = clear.
 * Every change names the version of each row it was based on; a different current version means someone else
 * changed it, and the request is refused (409 EDIT_CONFLICT) instead of overwriting.
 */
public final class ReviewViews {

    private ReviewViews() {
    }

    public static final int MAX_TEXT = 5_000;
    public static final String CATEGORIES = "ADULT_MALE|ADULT_FEMALE|BOY|GIRL|ELDERLY_MALE|ELDERLY_FEMALE|CUSTOM";

    public record VersionRef(@NotNull UUID id, @NotNull Long version) {
    }

    /** Line edit. sourceTextEdited = your Chinese correction, editedText = your Khmer; the recognised text never changes. */
    public record EditSegmentRequest(
            @NotNull Long version,
            @Size(max = MAX_TEXT) String sourceTextEdited,
            @Size(max = MAX_TEXT) String editedText,
            @Pattern(regexp = "PENDING|NEEDS_REVIEW|APPROVED|REJECTED") String reviewStatus,
            UUID speakerId,
            @Min(0) Long startMs,
            @Min(1) Long endMs) {
    }

    public record SplitPart(@Size(max = MAX_TEXT) String sourceTextEdited, @Size(max = MAX_TEXT) String editedText) {
    }

    /** Split before word {@code atWord} (1 .. words-1). If the line has your own text, say what each part gets. */
    public record SplitSegmentRequest(@NotNull Long version, @NotNull @Min(1) Integer atWord, @Valid SplitPart first,
                                      @Valid SplitPart second) {
    }

    /** Merge two neighbouring lines. speakerId is required when they have different speakers. */
    public record MergeSegmentsRequest(@NotNull @Valid VersionRef first, @NotNull @Valid VersionRef second,
                                       UUID speakerId) {
    }

    public record ReassignRequest(@NotNull UUID speakerId, @NotEmpty @Size(max = 2_000) List<@Valid VersionRef> segments) {
    }

    public record CreateSpeakerRequest(@Size(max = 150) String characterName, @Size(max = 2_000) String description,
                                       @Pattern(regexp = CATEGORIES) String suggestedCategory) {
    }

    /** voiceProfileId and suggestedCategory: "" clears. */
    public record UpdateSpeakerRequest(
            @NotNull Long version,
            @Size(max = 150) String characterName,
            @Size(max = 2_000) String description,
            @Pattern(regexp = "|" + CATEGORIES) String suggestedCategory,
            @Pattern(regexp = "UNREVIEWED|REVIEWED") String reviewStatus,
            @Pattern(regexp = "|[0-9a-fA-F-]{36}") String voiceProfileId,
            @Min(0) @Max(100_000) Integer displayOrder) {
    }

    public record MergeSpeakersRequest(@NotNull @Valid VersionRef target,
                                       @NotEmpty @Size(max = 200) List<@Valid VersionRef> sources) {
    }

    /** Move the given lines of this speaker to a NEW speaker. */
    public record SplitSpeakerRequest(@NotNull Long version, @NotEmpty @Size(max = 2_000) List<@Valid VersionRef> segments,
                                      @Size(max = 150) String characterName) {
    }

    public record OperationView(UUID id, long seq, String type, String summary, OffsetDateTime createdAt,
                                OffsetDateTime undoneAt, boolean undoable) {
    }

    /** What a change did: the operation (for undo) and the current state of every affected line and speaker. */
    public record OperationResult(OperationView operation, List<LineView> lines, List<SpeakerView> speakers,
                                  List<UUID> removedLineIds, List<UUID> removedSpeakerIds) {
    }
}
