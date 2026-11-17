package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.storage.StorageService;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/** Line audio: exact slices of the project's own audio, at most 60 s, never stored, nothing outside the project. */
@IntegrationTest
class LineAudioTest extends ReviewTestSupport {

    static final int RATE = 16_000;

    /** A 10 s, 16 kHz mono WAV whose sample i has the value (i % 30000), registered as the project's audio. */
    private Path audio(UUID project, String relative, byte[] content) throws Exception {
        Path file = storage.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
        assets.save(new MediaAsset(project, MediaAssetType.EXTRACTED_AUDIO, file.getFileName().toString(), relative,
                "audio/wav", Files.size(file), StorageService.sha256(file), null));
        return file;
    }

    static byte[] wav(int seconds) {
        int samples = seconds * RATE;
        ByteBuffer b = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x46464952).putInt(36 + samples * 2).putInt(0x45564157);
        b.putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16);
        b.putInt(0x61746164).putInt(samples * 2);
        for (int i = 0; i < samples; i++) {
            b.putShort((short) (i % 30_000));
        }
        return b.array();
    }

    private MockHttpServletResponse fetch(UUID project, String... params) throws Exception {
        var req = get("/api/projects/{p}/audio", project);
        for (int i = 0; i < params.length; i += 2) {
            req.param(params[i], params[i + 1]);
        }
        return mvc.perform(req).andReturn().getResponse();
    }

    private static long fileCount(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void returnsAnExactInMemorySliceAndStoresNothing() throws Exception {
        Dialogue d = activeDialogue();
        audio(d.project(), StorageService.projectDir(d.project()) + "/extracted/x/audio.wav", wav(10));
        Path projectDir = storage.resolve(StorageService.projectDir(d.project()));
        long files = fileCount(projectDir);

        MockHttpServletResponse res = fetch(d.project(), "fromMs", "1000", "toMs", "1500");
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).isEqualTo("audio/wav");
        assertThat(res.getHeader("Cache-Control")).contains("no-store");
        ByteBuffer b = ByteBuffer.wrap(res.getContentAsByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(b.getInt(0)).isEqualTo(0x46464952);
        assertThat(b.getInt(24)).isEqualTo(RATE);
        assertThat(b.getInt(40)).isEqualTo(8_000 * 2);
        for (int i = 0; i < 8_000; i++) {
            assertThat(b.getShort(44 + i * 2)).isEqualTo((short) ((16_000 + i) % 30_000));
        }
        // a line plus 250 ms on each side
        MockHttpServletResponse line = fetch(d.project(), "segmentId", d.lines().get(0).toString()); // 1000-3000 ms
        assertThat(line.getStatus()).isEqualTo(200);
        assertThat(line.getContentAsByteArray().length).isEqualTo(44 + (3_250 - 750) * RATE / 1000 * 2);
        // the end is clamped to the audio length
        assertThat(fetch(d.project(), "fromMs", "9000", "toMs", "20000").getContentAsByteArray().length)
                .isEqualTo(44 + RATE * 2);
        assertThat(fileCount(projectDir)).isEqualTo(files);
    }

    @Test
    void rangesAreValidatedAndLimitedTo60Seconds() throws Exception {
        Dialogue d = activeDialogue();
        audio(d.project(), StorageService.projectDir(d.project()) + "/extracted/x/audio.wav", wav(10));
        assertThat(fetch(d.project(), "fromMs", "0", "toMs", "60001").getStatus()).isEqualTo(400);
        assertThat(fetch(d.project(), "fromMs", "0", "toMs", "60000").getStatus()).isEqualTo(200);
        assertThat(fetch(d.project(), "fromMs", "500", "toMs", "500").getStatus()).isEqualTo(400);
        assertThat(fetch(d.project(), "fromMs", "-1", "toMs", "500").getStatus()).isEqualTo(400);
        assertThat(fetch(d.project(), "fromMs", "10000", "toMs", "11000").getStatus()).isEqualTo(400);
        assertThat(fetch(d.project()).getStatus()).isEqualTo(400);
        assertThat(fetch(d.project(), "fromMs", "abc", "toMs", "1").getStatus()).isEqualTo(400);
    }

    @Test
    void onlyTheProjectsOwnAudioInsideItsFolderIsServed() throws Exception {
        Dialogue d = activeDialogue();
        Dialogue other = activeDialogue();
        // no audio yet
        assertThat(fetch(d.project(), "fromMs", "0", "toMs", "100").getStatus()).isEqualTo(404);
        // an audio asset of this project whose file lies in ANOTHER project's folder is refused
        audio(d.project(), StorageService.projectDir(other.project()) + "/extracted/y/audio.wav", wav(2));
        assertThat(fetch(d.project(), "fromMs", "0", "toMs", "100").getStatus()).isEqualTo(404);
        // another project's line cannot be used to read this project's audio, nor the reverse
        audio(other.project(), StorageService.projectDir(other.project()) + "/extracted/z/audio.wav", wav(2));
        assertThat(fetch(d.project(), "segmentId", other.lines().get(0).toString()).getStatus()).isEqualTo(404);
        assertThat(fetch(UUID.randomUUID(), "fromMs", "0", "toMs", "100").getStatus()).isEqualTo(404);
        assertThat(fetch(other.project(), "fromMs", "0", "toMs", "100").getStatus()).isEqualTo(200);
    }

    /** A 10 s, 16 kHz mono WAV in which every sample has the same value. */
    static byte[] constantWav(int seconds, short value) {
        byte[] w = wav(seconds);
        ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < seconds * RATE; i++) {
            b.putShort(44 + i * 2, value);
        }
        return w;
    }

    /** The extracted audio this revision was made from (its source job's audioAssetId). */
    private void madeFrom(UUID project, UUID revision, UUID audioAsset) {
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO processing_jobs (id, project_id, job_type, status, progress, parameters) "
                + "VALUES (?, ?, 'TRANSCRIBE', 'COMPLETED', 100, ?::jsonb)", job, project,
                "{\"audioAssetId\": \"" + audioAsset + "\"}");
        jdbc.update("UPDATE dialogue_revisions SET source_job_id = ? WHERE id = ?", job, revision);
    }

    private UUID assetAt(String relative) {
        return jdbc.queryForObject("SELECT id FROM media_assets WHERE storage_path = ?", UUID.class, relative);
    }

    @Test
    void aLinePlaysTheAudioItsOwnRevisionWasMadeFrom() throws Exception {
        Dialogue one = activeDialogue();
        Dialogue two = revision(one.project(), 2, com.khmerdub.api.dialogue.DialogueRevision.Status.READY);
        String dir = StorageService.projectDir(one.project()) + "/extracted/";
        audio(one.project(), dir + "a1/audio.wav", wav(10));                       // sample i = i % 30000
        audio(one.project(), dir + "a2/audio.wav", constantWav(10, (short) 7));   // another audio track
        madeFrom(one.project(), one.revision(), assetAt(dir + "a1/audio.wav"));
        madeFrom(one.project(), two.revision(), assetAt(dir + "a2/audio.wav"));
        // revision 2 is now in use, revision 1 was used before (read-only page may still play its lines)
        jdbc.update("UPDATE dialogue_revisions SET status = 'SUPERSEDED', superseded_at = now() WHERE id = ?", one.revision());
        jdbc.update("UPDATE dialogue_revisions SET status = 'ACTIVE', activated_at = now() WHERE id = ?", two.revision());

        ByteBuffer old = ByteBuffer.wrap(fetch(one.project(), "segmentId", one.lines().get(0).toString()).getContentAsByteArray())
                .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(old.getShort(44)).isEqualTo((short) 12_000);   // 750 ms of the audio revision 1 was made from
        ByteBuffer current = ByteBuffer.wrap(fetch(one.project(), "segmentId", two.lines().get(0).toString()).getContentAsByteArray())
                .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(current.getShort(44)).isEqualTo((short) 7);
        ByteBuffer range = ByteBuffer.wrap(fetch(one.project(), "fromMs", "1000", "toMs", "1100").getContentAsByteArray())
                .order(ByteOrder.LITTLE_ENDIAN);
        assertThat(range.getShort(44)).isEqualTo((short) 7);      // a plain time range: the revision in use
    }

    @Test
    void aFileThatIsNotPcmWavIsRefused() throws Exception {
        Dialogue d = activeDialogue();
        audio(d.project(), StorageService.projectDir(d.project()) + "/extracted/x/audio.wav",
                "not a wav file at all".getBytes());
        MockHttpServletResponse res = fetch(d.project(), "fromMs", "0", "toMs", "100");
        assertThat(res.getStatus()).isEqualTo(409);
        assertThat(res.getContentAsString()).contains("AUDIO_UNREADABLE").doesNotContain("extracted").doesNotContain(":\\");
    }
}
