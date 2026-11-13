package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Database-level guarantees added by V2. */
@IntegrationTest
class SchemaV2Test {

    @Autowired JdbcTemplate jdbc;

    @Test
    void atMostOneLiveAttemptPerChunk() {
        UUID chunk = newChunk();
        insertAttempt(chunk, 1, "LOST");
        insertAttempt(chunk, 2, "RUNNING");
        assertThatThrownBy(() -> insertAttempt(chunk, 3, "DISPATCHED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_processing_attempts_one_live");
    }

    @Test
    void attemptNumbersAreUniquePerChunk() {
        UUID chunk = newChunk();
        insertAttempt(chunk, 1, "FAILED");
        assertThatThrownBy(() -> insertAttempt(chunk, 1, "LOST")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void nonTimeChunksHaveNoTimesAndTimeChunksMustBeValid() {
        UUID job = newJob();
        assertThatCode(() -> jdbc.update("INSERT INTO processing_chunks (job_id, stage, chunk_index) VALUES (?, 'INSPECT_MEDIA', 0)", job))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO processing_chunks (job_id, stage, chunk_index, start_ms) VALUES (?, 'EXTRACT_AUDIO', 0, 0)", job))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pausedAndCancellingJobsStillCountAsActive() {
        UUID project = jdbc.queryForObject("INSERT INTO dub_projects (name) VALUES ('t') RETURNING id", UUID.class);
        jdbc.update("INSERT INTO processing_jobs (project_id, job_type, status) VALUES (?, 'EXTRACT_AUDIO', 'CANCELLING')", project);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO processing_jobs (project_id, job_type, status) VALUES (?, 'EXTRACT_AUDIO', 'QUEUED')", project))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID newJob() {
        UUID project = jdbc.queryForObject("INSERT INTO dub_projects (name) VALUES ('t') RETURNING id", UUID.class);
        return jdbc.queryForObject("INSERT INTO processing_jobs (project_id, job_type, status) VALUES (?, 'IMPORT', 'COMPLETED') RETURNING id",
                UUID.class, project);
    }

    private UUID newChunk() {
        return jdbc.queryForObject("INSERT INTO processing_chunks (job_id, stage, chunk_index) VALUES (?, 'INSPECT_MEDIA', 0) RETURNING id",
                UUID.class, newJob());
    }

    private void insertAttempt(UUID chunk, int no, String status) {
        jdbc.update("INSERT INTO processing_attempts (id, chunk_id, attempt_no, status, claim_deadline) VALUES (?, ?, ?, ?, now())",
                UUID.randomUUID(), chunk, no, status);
    }
}
