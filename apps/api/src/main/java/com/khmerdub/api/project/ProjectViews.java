package com.khmerdub.api.project;

import com.khmerdub.api.job.JobViews.JobBrief;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ProjectViews {

    /** "auto" or an ISO 639 code such as zh, ko, ja, en, zh-Hant. */
    public static final String LANGUAGE_PATTERN = "auto|[a-z]{2,3}(-[A-Za-z]{2,4})?";

    private ProjectViews() {
    }

    public record CreateProjectRequest(
            @NotBlank(message = "Enter a project name") @Size(max = 200, message = "At most 200 characters") String name,
            @Pattern(regexp = LANGUAGE_PATTERN, message = "Use 'auto' or a language code such as zh, ko, ja, en")
            String sourceLanguage) {
    }

    public record UpdateProjectRequest(
            @Size(min = 1, max = 200, message = "1 to 200 characters") String name,
            @Pattern(regexp = LANGUAGE_PATTERN, message = "Use 'auto' or a language code such as zh, ko, ja, en")
            String sourceLanguage,
            @Min(value = 0, message = "Invalid stream index") Integer sourceAudioStreamIndex) {
    }

    public record StartExtractionRequest(
            @Min(value = 0, message = "Invalid stream index") Integer audioStreamIndex,
            @Pattern(regexp = LANGUAGE_PATTERN, message = "Use 'auto' or a language code such as zh, ko, ja, en")
            String sourceLanguage) {
    }

    public record UploadCheckRequest(@NotBlank String fileName, @Min(1) long sizeBytes) {
    }

    public record AssetView(UUID id, MediaAssetType type, String filename, String storagePath, String mimeType,
                            Long sizeBytes, String sha256, Map<String, Object> metadata, UUID producedByAttemptId,
                            OffsetDateTime createdAt) {

        public static AssetView of(MediaAsset a) {
            return a == null ? null : new AssetView(a.getId(), a.getType(), a.getFilename(), a.getStoragePath(),
                    a.getMimeType(), a.getFileSizeBytes(), a.getSha256(), a.getMetadata(), a.getProducedByAttemptId(),
                    a.getCreatedAt());
        }
    }

    /** One row of the dashboard. */
    public record ProjectSummary(UUID id, String name, ProjectStatus status, Long durationMs, String sourceLanguage,
                                 boolean archived, String originalFilename, Long originalSizeBytes,
                                 JobBrief activeJob, JobBrief lastJob, OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt) {
    }

    public record ProjectDetail(ProjectSummary summary, String targetLanguage, String translationStyle,
                                Integer sourceAudioStreamIndex, UUID processingProfileId, AssetView original,
                                AssetView extractedAudio, List<JobBrief> jobs) {
    }

    public record UploadResult(AssetView original, JobBrief importJob) {
    }

    public record StorageInfo(String root, long freeBytes, long totalBytes, long minFreeBytes, long maxUploadBytes,
                              long headroomBytes) {
    }
}
