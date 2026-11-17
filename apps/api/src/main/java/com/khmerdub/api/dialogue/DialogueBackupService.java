package com.khmerdub.api.dialogue;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Verified JSON backups of a dialogue revision (all lines, speakers, edits and assignments). A backup counts only
 * after the written file was re-read from disk, its SHA-256 matched and it parsed back to the same counts.
 */
@Service
public class DialogueBackupService {

    public static final String FORMAT = "khmerdub.dialogue-backup.v1";
    /**
     * What the user is told when a backup fails (code BACKUP_FAILED). Stable and free of internals: no paths, no
     * exception names. Whatever needed the backup did not happen, so the same request can simply be retried.
     */
    public static final String FAILED_MESSAGE = "The dialogue could not be backed up, so nothing was changed. "
            + "Check that the data drive has free space and can be written to, then try again.";
    private static final Logger log = LoggerFactory.getLogger(DialogueBackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final SpeakerRepository speakers;
    private final DialogueSegmentRepository segments;
    private final MediaAssetRepository assets;
    private final StorageService storage;
    private final ObjectMapper json;
    private final Clock clock;

    public DialogueBackupService(SpeakerRepository speakers, DialogueSegmentRepository segments,
                                 MediaAssetRepository assets, StorageService storage, ObjectMapper json, Clock clock) {
        this.speakers = speakers;
        this.segments = segments;
        this.assets = assets;
        this.storage = storage;
        this.json = json;
        this.clock = clock;
    }

    /** Write and verify a backup of the revision; register it as a DIALOGUE_BACKUP asset. Throws if anything fails. */
    public MediaAsset backup(DialogueRevision revision, String reason) {
        List<Map<String, Object>> speakerRows = speakers.findByRevisionIdOrderByDisplayOrderAsc(revision.getId())
                .stream().map(Speaker::export).toList();
        List<Map<String, Object>> lineRows = segments.findByRevisionIdOrderBySequenceNoAsc(revision.getId())
                .stream().map(DialogueSegment::export).toList();
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", FORMAT);
        doc.put("projectId", revision.getProjectId());
        doc.put("revisionId", revision.getId());
        doc.put("revisionNo", revision.getRevisionNo());
        doc.put("revisionStatus", revision.getStatus().name());
        doc.put("reason", reason);
        doc.put("exportedAt", now.toString());
        doc.put("speakers", speakerRows);
        doc.put("lines", lineRows);

        String name = "revision-%d-%s-%s.json".formatted(revision.getRevisionNo(), now.format(STAMP),
                UUID.randomUUID().toString().substring(0, 8));
        String relative = StorageService.projectDir(revision.getProjectId()) + "/dialogue/backups/" + name;
        Path target = storage.resolve(relative);
        Path temp = target.resolveSibling(name + ".partial");
        try {
            byte[] bytes = json.writeValueAsBytes(doc);
            Files.createDirectories(target.getParent());
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ch.write(java.nio.ByteBuffer.wrap(bytes));
                ch.force(true); // on disk before it counts
            }
            String expected = StorageService.sha256(temp);
            storage.publish(temp, target); // never overwrites
            verify(target, expected, speakerRows.size(), lineRows.size());
            MediaAsset asset = new MediaAsset(revision.getProjectId(), MediaAssetType.DIALOGUE_BACKUP, name, relative,
                    "application/json", Files.size(target), expected, null);
            asset.setMetadata(Map.of("revisionId", revision.getId().toString(), "revisionNo", revision.getRevisionNo(),
                    "lines", lineRows.size(), "speakers", speakerRows.size(), "verified", true, "reason", reason));
            return assets.save(asset);
        } catch (IOException | JacksonException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // nothing more to do
            }
            // Diagnostics stay in the server log: ids, the backup's storage-relative name and the kind of failure.
            // Never the absolute path (it can contain user folder names) or an I/O exception's message (it usually
            // is that path); only our own verification messages are logged verbatim.
            String why = e instanceof VerificationFailed ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("Dialogue backup failed: project {} revision {} (no. {}), file {}: {}", revision.getProjectId(),
                    revision.getId(), revision.getRevisionNo(), relative, why);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "BACKUP_FAILED", FAILED_MESSAGE);
        }
    }

    private void verify(Path file, String expectedSha, int speakerCount, int lineCount) throws IOException {
        if (!StorageService.sha256(file).equals(expectedSha)) {
            throw new VerificationFailed("backup checksum mismatch after writing");
        }
        Map<String, Object> back = json.readValue(file.toFile(), new TypeReference<Map<String, Object>>() { });
        if (!FORMAT.equals(back.get("format")) || !(back.get("speakers") instanceof List<?> s)
                || !(back.get("lines") instanceof List<?> l) || s.size() != speakerCount || l.size() != lineCount) {
            throw new VerificationFailed("backup content check failed");
        }
    }

    /** The written backup did not read back as written (our own message, safe to log). */
    private static final class VerificationFailed extends IOException {
        VerificationFailed(String message) {
            super(message);
        }
    }
}
