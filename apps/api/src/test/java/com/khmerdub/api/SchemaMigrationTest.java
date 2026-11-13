package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.project.ProjectStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class SchemaMigrationTest {

    private static final String HASH_A = "a".repeat(64);

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DubProjectRepository projects;

    @Test
    void migrationCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains(
                "dub_projects", "media_assets", "processing_jobs", "processing_chunks", "job_events", "speakers",
                "voice_profiles", "dialogue_segments", "audio_clips", "audio_clip_versions", "subtitle_tracks",
                "processing_profiles");
        String version = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(version).isEqualTo("2");
    }

    @Test
    void defaultProcessingProfileIsSeeded() {
        var row = jdbc.queryForMap("SELECT name, max_duration_ms, transcription_chunk_ms, source_separation_enabled "
                + "FROM processing_profiles WHERE is_default");
        assertThat(row.get("name")).isEqualTo("balanced");
        assertThat(row.get("max_duration_ms")).isEqualTo(10_800_000L);
        assertThat(row.get("transcription_chunk_ms")).isEqualTo(300_000L);
        assertThat(row.get("source_separation_enabled")).isEqualTo(false);
    }

    @Test
    void jpaEntityRoundTrips() {
        DubProject saved = projects.saveAndFlush(new DubProject("JPA round trip"));
        DubProject loaded = projects.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ProjectStatus.CREATED);
        assertThat(loaded.getTargetLanguage()).isEqualTo("km");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @Test
    void duplicateChunkIsRejected() {
        UUID job = newJob(newProject(), "TRANSCRIBE", "RUNNING");
        insertChunk(job, "TRANSCRIBE", 0, 0, 300_000);
        assertThatThrownBy(() -> insertChunk(job, "TRANSCRIBE", 0, 0, 300_000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_processing_chunks_job_stage_index");
    }

    @Test
    void chunkMustEndAfterItStarts() {
        UUID job = newJob(newProject(), "TRANSCRIBE", "RUNNING");
        assertThatThrownBy(() -> insertChunk(job, "TRANSCRIBE", 0, 5000, 5000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_processing_chunks_times");
    }

    @Test
    void onlyOneActiveJobPerTypeAndProject() {
        UUID project = newProject();
        UUID first = newJob(project, "TRANSCRIBE", "RUNNING");
        jdbc.update("UPDATE processing_jobs SET status = 'COMPLETED' WHERE id = ?", first);
        newJob(project, "TRANSCRIBE", "QUEUED"); // allowed: the first one is finished
        newJob(project, "TRANSLATE", "QUEUED");  // allowed: different type
        assertThatThrownBy(() -> newJob(project, "TRANSCRIBE", "PAUSED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_processing_jobs_one_active");
    }

    @ParameterizedTest
    @ValueSource(strings = {"D:/KhmerDub/data/x.mp4", "/etc/passwd", "projects\\a\\movie.mp4",
            "../outside.mp4", "projects/../../outside.mp4", ""})
    void storagePathMustBeRelativeAndSafe(String badPath) {
        UUID project = newProject();
        assertThatThrownBy(() -> insertAsset(project, "OTHER", badPath))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void relativeStoragePathIsAccepted() {
        UUID project = newProject();
        assertThatCode(() -> insertAsset(project, "ORIGINAL_VIDEO", "projects/" + project + "/original/movie..final.mp4"))
                .doesNotThrowAnyException();
    }

    @Test
    void onlyOneOriginalVideoPerProject() {
        UUID project = newProject();
        insertAsset(project, "ORIGINAL_VIDEO", "projects/" + project + "/original/a.mp4");
        assertThatThrownBy(() -> insertAsset(project, "ORIGINAL_VIDEO", "projects/" + project + "/original/b.mp4"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_media_assets_one_original");
    }

    @Test
    void sameTtsInputIsNeverStoredTwiceForAClip() {
        UUID project = newProject();
        UUID segment = jdbc.queryForObject("""
                INSERT INTO dialogue_segments (project_id, sequence_no, start_ms, end_ms, source_text)
                VALUES (?, 0, 1000, 2500, 'Hello') RETURNING id""", UUID.class, project);
        UUID clip = jdbc.queryForObject(
                "INSERT INTO audio_clips (dialogue_segment_id) VALUES (?) RETURNING id", UUID.class, segment);
        UUID asset1 = insertAsset(project, "TTS_CLIP", "projects/" + project + "/voices/c1-v1.wav");
        UUID asset2 = insertAsset(project, "TTS_CLIP", "projects/" + project + "/voices/c1-v2.wav");
        insertClipVersion(clip, 1, asset1, HASH_A);
        assertThatThrownBy(() -> insertClipVersion(clip, 2, asset2, HASH_A))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_audio_clip_versions_clip_hash");
    }

    @Test
    void projectWithAssetsCannotBeDeletedByCascade() {
        UUID project = newProject();
        insertAsset(project, "ORIGINAL_VIDEO", "projects/" + project + "/original/keep.mp4");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dub_projects WHERE id = ?", project))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private UUID newProject() {
        return jdbc.queryForObject("INSERT INTO dub_projects (name) VALUES ('test') RETURNING id", UUID.class);
    }

    private UUID newJob(UUID project, String type, String status) {
        return jdbc.queryForObject("INSERT INTO processing_jobs (project_id, job_type, status) VALUES (?, ?, ?) RETURNING id",
                UUID.class, project, type, status);
    }

    private void insertChunk(UUID job, String stage, int index, long startMs, long endMs) {
        jdbc.update("INSERT INTO processing_chunks (job_id, stage, chunk_index, start_ms, end_ms) VALUES (?, ?, ?, ?, ?)",
                job, stage, index, startMs, endMs);
    }

    private UUID insertAsset(UUID project, String type, String path) {
        return jdbc.queryForObject("""
                INSERT INTO media_assets (project_id, asset_type, filename, storage_path)
                VALUES (?, ?, 'f', ?) RETURNING id""", UUID.class, project, type, path);
    }

    private void insertClipVersion(UUID clip, int version, UUID asset, String hash) {
        jdbc.update("""
                INSERT INTO audio_clip_versions (audio_clip_id, version_no, asset_id, input_hash, provider, provider_voice_id)
                VALUES (?, ?, ?, ?, 'test', 'voice-1')""", clip, version, asset, hash);
    }
}
