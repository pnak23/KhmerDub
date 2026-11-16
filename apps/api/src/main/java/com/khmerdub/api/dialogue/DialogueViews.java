package com.khmerdub.api.dialogue;

import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** API shapes of the dialogue package. None of them contains speaker embeddings or private file paths. */
public final class DialogueViews {

    private DialogueViews() {
    }

    /**
     * @param mode             QUALITY (default, 1 s diarization step) or FAST (2 s step: faster, merges more voices)
     * @param language         "auto" or a language code such as "zh"
     * @param confirmReprocess required when the project already has an active dialogue (a verified backup is made)
     */
    public record StartTranscriptionRequest(
            @Pattern(regexp = "QUALITY|FAST", message = "must be QUALITY or FAST") String mode,
            @Pattern(regexp = "auto|[a-z]{2,3}", message = "must be 'auto' or a language code") String language,
            Boolean diarize,
            boolean confirmReprocess) {
    }

    public record ActivateRequest(boolean confirm) {
    }

    public record RevisionView(UUID id, int revisionNo, String status, String mode, Map<String, Object> summary,
                               UUID sourceJobId, UUID backupAssetId, OffsetDateTime createdAt,
                               OffsetDateTime activatedAt, OffsetDateTime supersededAt) {

        static RevisionView of(DialogueRevision r) {
            return new RevisionView(r.getId(), r.getRevisionNo(), r.getStatus().name(), r.getDiarizationMode(),
                    r.getSummary(), r.getSourceJobId(), r.getBackupAssetId(), r.getCreatedAt(), r.getActivatedAt(),
                    r.getSupersededAt());
        }
    }

    public record SpeakerView(UUID id, String code, String characterName, long speechMs, int segmentCount,
                              int displayOrder, String reviewStatus, UUID voiceProfileId, List<UUID> sampleAssetIds) {
    }

    public record LineView(UUID id, int seq, long startMs, long endMs, UUID speakerId, String sourceText,
                           String sourceTextEdited, String editedText, Float confidence, Float speakerPurity,
                           Map<String, Object> flags, String reviewStatus) {

        static LineView of(DialogueSegment s) {
            return new LineView(s.getId(), s.getSequenceNo(), s.getStartMs(), s.getEndMs(), s.getSpeakerId(),
                    s.getSourceText(), s.getSourceTextEdited(), s.getEditedText(), s.getConfidence(),
                    s.getSpeakerPurity(), s.getFlags(), s.getReviewStatus());
        }
    }

    public record DialoguePage(UUID revisionId, String revisionStatus, long total, int fromSeq, List<LineView> lines) {
    }
}
