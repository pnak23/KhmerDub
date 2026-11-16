package com.khmerdub.api.privacy;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.AttemptRepository;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Privacy controls for biometric-like data. Speaker embeddings ("voice fingerprints") and voice samples are kept in
 * separate private folders and are deleted separately. Every deletion removes the files from the file system (not a
 * recycle bin), re-scans to verify nothing is left, removes their asset records and records evidence (counts only).
 * Limitation: this is a file-system deletion, not a forensic wipe of the disk blocks.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);
    public static final String FORGET_FINGERPRINTS = "FORGET_FINGERPRINTS";
    public static final String DELETE_VOICE_SAMPLES = "DELETE_VOICE_SAMPLES";

    private final DubProjectRepository projects;
    private final JobRepository jobs;
    private final AttemptRepository attempts;
    private final MediaAssetRepository assets;
    private final PrivacyActionRepository actions;
    private final StorageService storage;
    private final Clock clock;

    public PrivacyService(DubProjectRepository projects, JobRepository jobs, AttemptRepository attempts,
                          MediaAssetRepository assets, PrivacyActionRepository actions, StorageService storage,
                          Clock clock) {
        this.projects = projects;
        this.jobs = jobs;
        this.attempts = attempts;
        this.assets = assets;
        this.actions = actions;
        this.storage = storage;
        this.clock = clock;
    }

    /** Forget voice fingerprints: delete all speaker embeddings of the project (files + records). */
    @Transactional
    public PrivacyAction forgetFingerprints(UUID projectId) {
        return delete(projectId, FORGET_FINGERPRINTS, "fingerprints", MediaAssetType.SPEAKER_EMBEDDING);
    }

    /** Delete the voice samples (short audio excerpts per speaker) of the project. */
    @Transactional
    public PrivacyAction deleteVoiceSamples(UUID projectId) {
        return delete(projectId, DELETE_VOICE_SAMPLES, "voice-samples", MediaAssetType.SPEAKER_SAMPLE);
    }

    private PrivacyAction delete(UUID projectId, String action, String kind, MediaAssetType type) {
        DubProject project = projects.lockById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        boolean busy = jobs.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .anyMatch(j -> j.getType() == JobType.TRANSCRIBE && JobStatus.ACTIVE.contains(j.getStatus()));
        if (busy) {
            throw ApiException.conflict("PROJECT_BUSY",
                    "Speakers are being processed for this project. Cancel or finish that job first.");
        }
        try {
            long[] main = storage.deleteTree(storage.resolve(StorageService.privateDir(projectId, kind)));
            long[] leftovers = deleteAttemptLeftovers(projectId, kind);
            List<MediaAsset> rows = assets.findByProjectIdAndTypeOrderByCreatedAtAsc(projectId, type);
            assets.deleteAll(rows);
            long remaining = main[2] + leftovers[2] + countFiles(storage.resolve(StorageService.privateDir(projectId, kind)));
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (type == MediaAssetType.SPEAKER_EMBEDDING) {
                project.setVoiceFingerprintsForgottenAt(now);
            } else {
                project.setVoiceSamplesDeletedAt(now);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("assetRecordsDeleted", rows.size());
            details.put("attemptLeftoverFiles", leftovers[0]);
            details.put("filesRemaining", remaining);
            PrivacyAction recorded = actions.save(new PrivacyAction(projectId, action, (int) (main[0] + leftovers[0]),
                    main[1] + leftovers[1], remaining == 0, details));
            log.info("Privacy action {} on project {}: {} file(s), verified={}", action, projectId,
                    recorded.getFilesDeleted(), recorded.isVerified());
            return recorded;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete private data", e);
        }
    }

    /** Private outputs still inside attempt scratch folders of this project (e.g. from a failed attempt). */
    private long[] deleteAttemptLeftovers(UUID projectId, String kind) throws IOException {
        Path attemptsDir = storage.resolve(StorageService.projectDir(projectId) + "/temp/attempts");
        long[] total = {0, 0, 0};
        if (!Files.isDirectory(attemptsDir)) {
            return total;
        }
        try (Stream<Path> dirs = Files.list(attemptsDir)) {
            for (Path attempt : dirs.toList()) {
                long[] r = storage.deleteTree(attempt.resolve("private").resolve(kind));
                total[0] += r[0];
                total[1] += r[1];
                total[2] += r[2];
            }
        }
        return total;
    }

    /**
     * Scheduler sweep: remove the private folder of every attempt that is no longer live (catches late writes by a
     * worker whose lease already expired). Returns the number of files removed.
     */
    public long sweepEndedAttempts() {
        Path projectsDir = storage.resolve("projects");
        long removed = 0;
        if (!Files.isDirectory(projectsDir)) {
            return 0;
        }
        // projects/{project}/temp/attempts/{attempt}/private is 5 levels below projects/
        try (Stream<Path> found = Files.walk(projectsDir, 5)) {
            for (Path p : found.filter(x -> x.getFileName().toString().equals("private")
                    && x.getParent() != null && x.getParent().getParent() != null
                    && x.getParent().getParent().getFileName().toString().equals("attempts")).toList()) {
                UUID attemptId;
                try {
                    attemptId = UUID.fromString(p.getParent().getFileName().toString());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                boolean live = attempts.findById(attemptId).map(a -> a.getStatus().live()).orElse(false);
                if (!live) {
                    removed += storage.deleteTree(p)[0];
                }
            }
        } catch (IOException e) {
            log.debug("Private sweep incomplete: {}", e.toString());
        }
        return removed;
    }

    /** Counts of private data on disk and in the database (never contents). */
    @Transactional(readOnly = true)
    public Map<String, Object> summary(UUID projectId) {
        DubProject project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        Map<String, Object> m = new LinkedHashMap<>();
        for (String kind : List.of("fingerprints", "voice-samples")) {
            Path dir = storage.resolve(StorageService.privateDir(projectId, kind));
            long[] usage = usage(dir);
            m.put(kind.equals("fingerprints") ? "fingerprintFiles" : "voiceSampleFiles", usage[0]);
            m.put(kind.equals("fingerprints") ? "fingerprintBytes" : "voiceSampleBytes", usage[1]);
        }
        m.put("fingerprintsForgottenAt", project.getVoiceFingerprintsForgottenAt());
        m.put("voiceSamplesDeletedAt", project.getVoiceSamplesDeletedAt());
        m.put("actions", actions.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(a -> Map.of(
                "action", a.getAction(), "filesDeleted", a.getFilesDeleted(), "bytesDeleted", a.getBytesDeleted(),
                "verified", a.isVerified(), "createdAt", a.getCreatedAt().toString())).toList());
        return m;
    }

    private static long countFiles(Path dir) throws IOException {
        return usage(dir)[0];
    }

    private static long[] usage(Path dir) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return new long[] {0, 0};
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            long[] u = {0, 0};
            for (Path p : walk.filter(x -> Files.isRegularFile(x, LinkOption.NOFOLLOW_LINKS)).toList()) {
                u[0]++;
                u[1] += p.toFile().length();
            }
            return u;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
