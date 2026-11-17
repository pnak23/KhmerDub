package com.khmerdub.api.review;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plays a short piece of a project's extracted audio (for listening to a line while reviewing).
 *
 * <p>Security: the audio comes only from the project's own EXTRACTED_AUDIO asset (the one the active dialogue was
 * made from), the file must lie inside that project's folder and outside any private folder, and a request covers
 * at most 60 s. The slice is cut in memory and never stored; nothing about the file path is returned or logged.
 */
@Service
public class LineAudioService {

    public static final long MAX_MS = 60_000;
    static final long PAD_MS = 250;
    private static final int MAX_HEADER = 1 << 20;

    private final MediaAssetRepository assets;
    private final StorageService storage;
    private final JdbcTemplate jdbc;

    public LineAudioService(MediaAssetRepository assets, StorageService storage, JdbcTemplate jdbc) {
        this.assets = assets;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    /** revisionId: the revision of the requested line (null for a plain time range: the revision in use). */
    public record Range(long fromMs, long toMs, UUID revisionId) {
    }

    /** A WAV file (16-bit PCM, mono) of [fromMs, toMs), or of a line plus a little padding when segmentId is given. */
    @Transactional(readOnly = true)
    public byte[] slice(UUID projectId, Long fromMs, Long toMs, UUID segmentId) {
        if (jdbc.queryForList("SELECT 1 FROM dub_projects WHERE id = ?", Integer.class, projectId).isEmpty()) {
            throw ApiException.notFound("Project");
        }
        Range range = segmentId != null ? lineRange(projectId, segmentId) : requested(fromMs, toMs);
        Path file = audioFile(projectId, range.revisionId());
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            Wav wav = Wav.read(ch);
            long durationMs = wav.dataBytes * 1000L / ((long) wav.sampleRate * 2);
            if (range.fromMs() >= durationMs) {
                throw ApiException.badRequest("AUDIO_RANGE_OUTSIDE", "The requested time is after the end of the audio.");
            }
            long to = Math.min(range.toMs(), durationMs);
            long firstSample = range.fromMs() * wav.sampleRate / 1000;
            long lastSample = to * wav.sampleRate / 1000;
            int bytes = (int) ((lastSample - firstSample) * 2);
            ByteBuffer out = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
            header(out, wav.sampleRate, bytes);
            ByteBuffer data = out.slice(44, bytes);
            long pos = wav.dataOffset + firstSample * 2;
            while (data.hasRemaining()) {
                int n = ch.read(data, pos);
                if (n < 0) {
                    break;
                }
                pos += n;
            }
            return out.array();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.CONFLICT, "AUDIO_UNREADABLE", "The project's audio could not be read.");
        }
    }

    private Range requested(Long fromMs, Long toMs) {
        if (fromMs == null || toMs == null) {
            throw ApiException.badRequest("AUDIO_RANGE_INVALID", "Give fromMs and toMs, or a segmentId.");
        }
        if (fromMs < 0 || toMs <= fromMs) {
            throw ApiException.badRequest("AUDIO_RANGE_INVALID", "toMs must be after fromMs, and fromMs at least 0.");
        }
        if (toMs - fromMs > MAX_MS) {
            throw ApiException.badRequest("AUDIO_RANGE_TOO_LONG", "At most 60 seconds of audio per request.");
        }
        return new Range(fromMs, toMs, null);
    }

    /** The line's own time plus 250 ms on each side, capped at 60 s. Any revision of the project. */
    private Range lineRange(UUID projectId, UUID segmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT start_ms, end_ms, revision_id FROM dialogue_segments WHERE id = ? AND project_id = ?", segmentId,
                projectId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Line");
        }
        long from = Math.max(0, ((Number) rows.getFirst().get("start_ms")).longValue() - PAD_MS);
        long to = Math.min(((Number) rows.getFirst().get("end_ms")).longValue() + PAD_MS, from + MAX_MS);
        return new Range(from, to, (UUID) rows.getFirst().get("revision_id"));
    }

    /**
     * The extracted audio a revision was made from: the line's own revision, or the one in use for a plain time range
     * (else the newest extracted audio), inside the project folder.
     */
    private Path audioFile(UUID projectId, UUID revisionId) {
        MediaAsset audio = null;
        List<Object> fromJob = revisionId != null
                ? jdbc.queryForList("""
                SELECT j.parameters ->> 'audioAssetId' FROM dialogue_revisions r JOIN processing_jobs j ON j.id = r.source_job_id
                 WHERE r.project_id = ? AND r.id = ?""", Object.class, projectId, revisionId)
                : jdbc.queryForList("""
                SELECT j.parameters ->> 'audioAssetId' FROM dialogue_revisions r JOIN processing_jobs j ON j.id = r.source_job_id
                 WHERE r.project_id = ? AND r.status = 'ACTIVE'""", Object.class, projectId);
        if (!fromJob.isEmpty() && fromJob.getFirst() != null) {
            try {
                audio = assets.findById(UUID.fromString(fromJob.getFirst().toString())).orElse(null);
            } catch (IllegalArgumentException ignored) {
                audio = null;
            }
        }
        if (audio == null) {
            List<MediaAsset> all = assets.findByProjectIdAndTypeOrderByCreatedAtAsc(projectId, MediaAssetType.EXTRACTED_AUDIO);
            audio = all.isEmpty() ? null : all.getLast();
        }
        if (audio == null || audio.getType() != MediaAssetType.EXTRACTED_AUDIO || !audio.getProjectId().equals(projectId)) {
            throw ApiException.notFound("Audio of the project");
        }
        Path file;
        try {
            file = storage.resolve(audio.getStoragePath());
        } catch (StorageService.UnsafePathException e) {
            throw ApiException.notFound("Audio of the project");
        }
        Path projectRoot = storage.resolve(StorageService.projectDir(projectId));
        if (!file.startsWith(projectRoot) || file.toString().replace('\\', '/').contains("/private/")
                || !Files.isRegularFile(file)) {
            throw ApiException.notFound("Audio of the project");
        }
        return file;
    }

    /** Where the samples of a 16-bit mono PCM WAV file are. */
    static final class Wav {
        int sampleRate;
        long dataOffset;
        long dataBytes;

        static Wav read(FileChannel ch) throws IOException {
            ByteBuffer h = ByteBuffer.allocate((int) Math.min(MAX_HEADER, ch.size())).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(h, 0);
            h.flip();
            if (h.remaining() < 12 || h.getInt(0) != 0x46464952 || h.getInt(8) != 0x45564157) { // "RIFF" ... "WAVE"
                throw unsupported();
            }
            Wav w = new Wav();
            boolean fmt = false;
            long cursor = 12;
            while (cursor + 8 <= h.limit()) {
                int pos = (int) cursor; // < 1 MiB
                int id = h.getInt(pos);
                long size = Integer.toUnsignedLong(h.getInt(pos + 4));
                if (id == 0x20746d66) { // "fmt "
                    if (size < 16 || pos + 8 + Math.min(size, 40) > h.limit()) {
                        throw unsupported();
                    }
                    int format = Short.toUnsignedInt(h.getShort(pos + 8));
                    int channels = Short.toUnsignedInt(h.getShort(pos + 10));
                    w.sampleRate = h.getInt(pos + 12);
                    int bits = Short.toUnsignedInt(h.getShort(pos + 22));
                    boolean pcm = format == 1 || (format == 0xFFFE && size >= 26 && pos + 34 <= h.limit()
                            && h.getShort(pos + 32) == 1);
                    if (!pcm || channels != 1 || bits != 16 || w.sampleRate <= 0 || w.sampleRate > 192_000) {
                        throw unsupported();
                    }
                    fmt = true;
                } else if (id == 0x61746164) { // "data"
                    if (!fmt) {
                        throw unsupported();
                    }
                    w.dataOffset = cursor + 8;
                    long available = ch.size() - w.dataOffset;
                    w.dataBytes = (size == 0 || size == 0xFFFFFFFFL || size > available) ? available : size;
                    w.dataBytes -= w.dataBytes % 2;
                    return w;
                }
                cursor += 8 + size + (size & 1);
            }
            throw unsupported();
        }

        private static IOException unsupported() {
            return new IOException("unsupported WAV layout");
        }
    }

    private static void header(ByteBuffer b, int sampleRate, int dataBytes) {
        b.putInt(0x46464952).putInt(36 + dataBytes).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1).putInt(sampleRate)
                .putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(dataBytes);
    }
}
