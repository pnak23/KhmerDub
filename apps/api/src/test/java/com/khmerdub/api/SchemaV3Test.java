package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V3: dialogue revisions, private storage locations, M3a-verified profile defaults, no vector columns. */
@IntegrationTest
class SchemaV3Test {

    @Autowired JdbcTemplate jdbc;

    UUID project;

    @BeforeEach
    void setUp() {
        jdbc.execute(EngineFixtures.TRUNCATE);
        project = UUID.fromString(jdbc.queryForObject("INSERT INTO dub_projects (name) VALUES ('v3') RETURNING id", String.class));
    }

    private UUID revision(int no, String status) {
        return UUID.fromString(jdbc.queryForObject("""
                INSERT INTO dialogue_revisions (project_id, revision_no, status, activated_at)
                VALUES (?, ?, ?, CASE WHEN ? IN ('ACTIVE', 'SUPERSEDED') THEN now() END) RETURNING id""",
                String.class, project, no, status, status));
    }

    @Test
    void speakersAndLinesBelongToARevisionAndOnlyOneRevisionIsActive() {
        revision(1, "ACTIVE");
        assertThatThrownBy(() -> revision(2, "ACTIVE")).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_dialogue_revisions_one_active");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO speakers (project_id, speaker_code) VALUES (?, 'SPK_01')", project))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("revision_id");
        UUID ready = revision(2, "READY");
        jdbc.update("INSERT INTO speakers (project_id, revision_id, speaker_code) VALUES (?, ?, 'SPK_01')", project, ready);
        jdbc.update("DELETE FROM dialogue_revisions WHERE id = ?", ready);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM speakers", Long.class)).isZero();
    }

    @Test
    void privateDataOnlyInItsOwnFolders() {
        String base = "projects/" + project;
        assertThatThrownBy(() -> asset("SPEAKER_SAMPLE", base + "/private/fingerprints/s.wav"))
                .hasMessageContaining("ck_media_assets_private_locations");
        assertThatThrownBy(() -> asset("TRANSCRIPT", base + "/private/voice-samples/t.json"))
                .hasMessageContaining("ck_media_assets_private_locations");
        asset("SPEAKER_EMBEDDING", base + "/private/fingerprints/e.npy");
        asset("SPEAKER_SAMPLE", base + "/private/voice-samples/s.wav");
    }

    private void asset(String type, String path) {
        jdbc.update("INSERT INTO media_assets (project_id, asset_type, filename, storage_path) VALUES (?, ?, 'f', ?)",
                project, type, path);
    }

    @Test
    void balancedProfileHasTheVerifiedDefaultsAndExistingValuesAreUnchanged() {
        Map<String, Object> p = jdbc.queryForMap("SELECT * FROM processing_profiles WHERE name = 'balanced'");
        assertThat(p).containsEntry("whisper_beam_size", 5).containsEntry("whisper_cpu_threads", 4)
                .containsEntry("diarization_threads", 8).containsEntry("diarization_mode", "QUALITY")
                .containsEntry("diarization_chunk_ms", 600_000L).containsEntry("diarization_overlap_ms", 60_000L)
                .containsEntry("transcription_chunk_ms", 300_000L).containsEntry("transcription_overlap_ms", 5_000L)
                .containsEntry("max_duration_ms", 10_800_000L);
        assertThat(((Number) p.get("diarization_step_quality")).doubleValue()).isEqualTo(0.1);
        assertThat(((Number) p.get("diarization_step_fast")).doubleValue()).isEqualTo(0.2);
    }

    @Test
    void noColumnCanHoldAnEmbeddingVector() {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'
                  AND (column_name ~* 'embedding|vector|centroid' OR data_type IN ('bytea', 'ARRAY'))""", Long.class);
        assertThat(n).isZero();
    }
}
