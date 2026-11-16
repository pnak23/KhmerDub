package com.khmerdub.api.job;

import com.khmerdub.api.job.EngineExceptions.InvalidOutputException;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.Output;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Verifies a worker output (inside the attempt's own folder, size and SHA-256 as reported) and moves it to its
 * final, never-overwritten location, then registers it as a media asset. Used by all stage planners.
 */
@Component
public class OutputPublisher {

    private final MediaAssetRepository assets;
    private final StorageService storage;

    public OutputPublisher(MediaAssetRepository assets, StorageService storage) {
        this.assets = assets;
        this.storage = storage;
    }

    /**
     * @param targetRelative path relative to the storage root (e.g. projects/{id}/transcription/...)
     */
    public MediaAsset publish(ProcessingJob job, ProcessingAttempt attempt, Output out, MediaAssetType type,
                              String targetRelative, String mime, Map<String, Object> metadata) {
        String workPrefix = StorageService.attemptWorkDir(job.getProjectId(), attempt.getId()) + "/";
        if (!out.path().startsWith(workPrefix)) {
            throw new InvalidOutputException("Output is outside this attempt's folder: " + out.path());
        }
        Path source = storage.resolve(out.path());
        Path target = storage.resolve(targetRelative);
        try {
            if (!Files.exists(source)) {
                // A retried completion after an earlier commit failure: accept only if the published file matches.
                if (Files.exists(target) && Files.size(target) == out.sizeBytes()
                        && StorageService.sha256(target).equals(out.sha256())) {
                    return register(job, attempt, type, target, targetRelative, mime, out, metadata);
                }
                throw new InvalidOutputException("Output file is missing: " + out.path());
            }
            long size = Files.size(source);
            if (size != out.sizeBytes()) {
                throw new InvalidOutputException("Size mismatch for %s: worker said %d, file has %d"
                        .formatted(out.path(), out.sizeBytes(), size));
            }
            String hash = StorageService.sha256(source);
            if (!hash.equals(out.sha256())) {
                throw new InvalidOutputException("Checksum mismatch for " + out.path());
            }
            storage.publish(source, target);
        } catch (FileAlreadyExistsException e) {
            throw new InvalidOutputException("Refusing to overwrite existing file " + targetRelative);
        } catch (IOException e) {
            throw new InvalidOutputException("Could not publish %s: %s".formatted(out.path(), e.getMessage()));
        }
        return register(job, attempt, type, target, targetRelative, mime, out, metadata);
    }

    private MediaAsset register(ProcessingJob job, ProcessingAttempt attempt, MediaAssetType type, Path target,
                                String relative, String mime, Output out, Map<String, Object> metadata) {
        MediaAsset asset = new MediaAsset(job.getProjectId(), type, target.getFileName().toString(), relative, mime,
                out.sizeBytes(), out.sha256(), attempt.getId());
        Map<String, Object> md = new HashMap<>(metadata);
        md.values().removeIf(java.util.Objects::isNull);
        asset.setMetadata(md);
        return assets.save(asset);
    }

    public static Output single(CompleteRequest result, String role) {
        List<Output> matches = result.outputsOrEmpty().stream().filter(o -> role.equals(o.role())).toList();
        if (matches.size() != 1) {
            throw new InvalidOutputException(matches.isEmpty() ? "Missing output '" + role + "'"
                    : "Expected one '%s' output, got %d".formatted(role, matches.size()));
        }
        return matches.getFirst();
    }

    public static List<Output> all(CompleteRequest result, String role) {
        return result.outputsOrEmpty().stream().filter(o -> role.equals(o.role())).toList();
    }
}
