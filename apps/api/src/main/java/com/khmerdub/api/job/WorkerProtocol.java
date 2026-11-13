package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEnums.Stage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Messages exchanged with workers on /internal/attempts/{id}/... */
public final class WorkerProtocol {

    private WorkerProtocol() {
    }

    public record ClaimRequest(@NotBlank @Size(max = 200) String worker) {
    }

    /** Everything the worker needs. Paths are relative to the storage root. */
    public record ClaimResponse(UUID attemptId, int attemptNo, UUID jobId, UUID projectId, Stage stage, int chunkIndex,
                                Long startMs, Long endMs, String workDir, long heartbeatIntervalSeconds,
                                long leaseSeconds, Map<String, Object> input) {
    }

    public record HeartbeatRequest(@Min(0) @Max(100) double progress) {
    }

    public enum Action { CONTINUE, CANCEL }

    public record HeartbeatResponse(Action action, long leaseSeconds) {
    }

    public record Output(@NotBlank String role, @NotBlank @Size(max = 1000) String path, @Min(0) long sizeBytes,
                         @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String sha256, Map<String, Object> info) {
    }

    public record CompleteRequest(@Valid List<Output> outputs, Map<String, Object> metadata,
                                  Map<String, Object> metrics) {

        public List<Output> outputsOrEmpty() {
            return outputs == null ? List.of() : outputs;
        }
    }

    public record FailRequest(boolean retryable, boolean cancelled, @Size(max = 20000) String error,
                              Map<String, Object> metrics) {
    }
}
