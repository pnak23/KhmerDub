package com.khmerdub.api.media;

import com.khmerdub.api.config.KhmerDubProperties;
import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobViews.JobBrief;
import com.khmerdub.api.job.ProcessingJob;
import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.project.ProjectViews.AssetView;
import com.khmerdub.api.project.ProjectViews.UploadResult;
import com.khmerdub.api.storage.MediaFiles;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Streams an uploaded movie to disk without buffering it in memory: checks name, size, free space and the
 * file's first bytes, writes to a hidden .part file while hashing, then renames it into original/ (never
 * replacing anything), marks it read-only, registers it and queues the IMPORT (inspection) job.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final int BUFFER = 1 << 20;

    private final DubProjectRepository projects;
    private final MediaAssetRepository assets;
    private final JobEngine engine;
    private final StorageService storage;
    private final KhmerDubProperties props;
    private final TransactionTemplate tx;
    private final Set<UUID> uploading = ConcurrentHashMap.newKeySet();

    public UploadService(DubProjectRepository projects, MediaAssetRepository assets, JobEngine engine,
                         StorageService storage, KhmerDubProperties props, TransactionTemplate tx) {
        this.projects = projects;
        this.assets = assets;
        this.engine = engine;
        this.storage = storage;
        this.props = props;
        this.tx = tx;
    }

    /** Validate before any bytes are sent. Returns the sanitized name and extension. */
    public Checked check(UUID projectId, String rawFileName, long sizeBytes) {
        DubProject project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        if (project.isArchived()) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived.");
        }
        if (assets.findFirstByProjectIdAndType(projectId, MediaAssetType.ORIGINAL_VIDEO).isPresent()) {
            throw ApiException.conflict("MOVIE_EXISTS", "This project already has a movie. Create a new project for another movie.");
        }
        String name = MediaFiles.sanitize(rawFileName);
        String ext = MediaFiles.extension(name).orElse("");
        if (!MediaFiles.MOVIE_TYPES.containsKey(ext)) {
            throw ApiException.unsupportedMedia("Only MP4, MKV and MOV movies are supported.");
        }
        if (sizeBytes <= 0) {
            throw ApiException.badRequest("EMPTY_FILE", "The file is empty.");
        }
        long max = props.upload().maxSize().toBytes();
        if (sizeBytes > max) {
            throw ApiException.tooLarge("The file is %.1f GB; the limit is %.1f GB.".formatted(gb(sizeBytes), gb(max)));
        }
        long free = storage.freeBytes();
        long required = sizeBytes + props.upload().headroom().toBytes() + props.minFreeDiskBytes();
        if (free < required) {
            throw ApiException.insufficientStorage(("Not enough free disk space: %.1f GB free, %.1f GB needed "
                    + "(movie + %.0f GB working space + %d GB minimum free).").formatted(gb(free), gb(required),
                    gb(props.upload().headroom().toBytes()), props.minFreeDiskGb()));
        }
        return new Checked(name, ext);
    }

    public UploadResult receive(UUID projectId, String rawFileName, long contentLength, InputStream body) {
        Checked checked = check(projectId, rawFileName, contentLength);
        if (!uploading.add(projectId)) {
            throw ApiException.conflict("UPLOAD_IN_PROGRESS", "An upload for this project is already in progress.");
        }
        Path part = null;
        try {
            Path originalDir = storage.resolve(StorageService.projectDir(projectId) + "/original");
            Files.createDirectories(originalDir);
            removeStaleParts(originalDir);
            part = originalDir.resolve(".upload-" + UUID.randomUUID() + ".part");

            byte[] buffer = new byte[BUFFER];
            int headLength = body.readNBytes(buffer, 0, 64);
            if (!MediaFiles.contentMatches(checked.extension(), buffer, headLength)) {
                throw ApiException.unsupportedMedia("The file's content does not look like a %s movie."
                        .formatted(checked.extension().toUpperCase()));
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(part), digest)) {
                out.write(buffer, 0, headLength);
                written = headLength;
                for (int n; (n = body.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    written += n;
                    if (written > contentLength) {
                        throw ApiException.badRequest("SIZE_MISMATCH", "More data than announced was received.");
                    }
                }
            }
            if (written != contentLength) {
                throw ApiException.badRequest("UPLOAD_INCOMPLETE",
                        "The upload was interrupted (%d of %d bytes received). Try again.".formatted(written, contentLength));
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());

            Path target = uniqueTarget(originalDir, checked.name());
            storage.publish(part, target);
            part = null;
            storage.markReadOnly(target);
            String relative = storage.relativize(target);
            long size = written;
            return tx.execute(status -> {
                MediaAsset asset = assets.save(new MediaAsset(projectId, MediaAssetType.ORIGINAL_VIDEO,
                        target.getFileName().toString(), relative, MediaFiles.MOVIE_TYPES.get(checked.extension()),
                        size, sha256, null));
                asset.setMetadata(new java.util.HashMap<>(Map.of("originalFileName", rawFileName)));
                ProcessingJob job = engine.createJob(projectId, JobType.IMPORT, Map.of("originalAssetId", asset.getId().toString()));
                return new UploadResult(AssetView.of(asset), JobBrief.of(job));
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store the upload", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            uploading.remove(projectId);
            if (part != null) {
                try {
                    Files.deleteIfExists(part); // only the hidden partial file of this upload
                } catch (IOException e) {
                    log.warn("Could not remove partial upload {}: {}", part, e.toString());
                }
            }
        }
    }

    /** Partial files from interrupted uploads (only `.upload-*.part`, never a movie). */
    private void removeStaleParts(Path originalDir) throws IOException {
        try (Stream<Path> files = Files.list(originalDir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().matches("\\.upload-[0-9a-f-]{36}\\.part")).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static Path uniqueTarget(Path dir, String name) {
        Path target = dir.resolve(name);
        int dot = name.lastIndexOf('.');
        for (int i = 1; Files.exists(target); i++) {
            target = dir.resolve(name.substring(0, dot) + " (" + i + ")" + name.substring(dot));
        }
        return target;
    }

    private static double gb(long bytes) {
        return bytes / (1024.0 * 1024 * 1024);
    }

    public record Checked(String name, String extension) {
    }
}
