package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.khmerdub.api.dialogue.DialogueBackupService;
import com.khmerdub.api.dialogue.DialogueRevision;
import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A failed dialogue backup answers BACKUP_FAILED with a stable, friendly message: no paths, exception names or stack
 * traces reach the client. The server log keeps a diagnostic line (ids, relative file name, failure kind) without the
 * absolute storage path. Nothing changes, and the same request works once the backup can be written.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class BackupFailureTest extends ReviewTestSupport {

    @Autowired JobRepository jobs;
    @Autowired TranscriptionService transcription;

    /** A regular file where the project's backup folder belongs: the next backup cannot be written. */
    private Path blockBackups(UUID project) throws Exception {
        Path folder = storage.resolve(StorageService.projectDir(project) + "/dialogue/backups");
        Files.createDirectories(folder.getParent());
        assertThat(folder).doesNotExist(); // a fresh project: nothing to set aside
        Files.writeString(folder, "blocks the backup folder");
        return folder;
    }

    private void assertSafeFailure(Map<String, Object> body) {
        assertThat(body).containsEntry("code", "BACKUP_FAILED").containsEntry("status", 500)
                .containsEntry("detail", DialogueBackupService.FAILED_MESSAGE);
        Map<String, Object> rest = new java.util.HashMap<>(body);
        rest.remove("instance"); // the request URI the client itself sent
        String text = rest.toString();
        String root = storage.root().toString();
        assertThat(text).doesNotContain("Exception").doesNotContain("FileAlready").doesNotContain("java.")
                .doesNotContain(" at com.").doesNotContain(root).doesNotContain(root.replace('\\', '/'))
                .doesNotContain("projects/").doesNotContain("backups").doesNotContain(".json").doesNotContain(".partial");
    }

    private void assertDiagnosticLogged(CapturedOutput output, UUID project, UUID revision) {
        String root = storage.root().toString();
        String line = output.getAll().lines().filter(l -> l.contains("Dialogue backup failed")).reduce((a, b) -> b).orElseThrow();
        assertThat(line).contains(project.toString()).contains(revision.toString())
                .contains("projects/" + project + "/dialogue/backups/revision-").contains("Exception")
                .doesNotContain(root).doesNotContain(root.replace('\\', '/'));
    }

    @Test
    void aFailedBackupBeforeSwitchingIsReportedSafelyAndTheSameRequestWorksAfterwards(CapturedOutput output) throws Exception {
        Dialogue a = activeDialogue();
        Dialogue b = revision(a.project(), 2, DialogueRevision.Status.READY);
        Map<String, Object> aWork = export(a.revision());
        Path folder = blockBackups(a.project());
        try {
            assertSafeFailure(send(post("/api/projects/{p}/dialogue-revisions/{r}/activate", a.project(), b.revision()),
                    map("confirm", true), 500));
            assertDiagnosticLogged(output, a.project(), a.revision());
            assertThat(revisions.findById(a.revision()).orElseThrow().getStatus()).isEqualTo(DialogueRevision.Status.ACTIVE);
            assertThat(revisions.findById(b.revision()).orElseThrow().getStatus()).isEqualTo(DialogueRevision.Status.READY);
            assertThat(export(a.revision())).isEqualTo(aWork);
        } finally {
            Files.delete(folder); // the blocking file this test created (not a backup)
        }
        // retry: same request, now succeeds with a verified backup
        Map<String, Object> view = send(post("/api/projects/{p}/dialogue-revisions/{r}/activate", a.project(), b.revision()),
                map("confirm", true), 200);
        assertThat(view).containsEntry("status", "ACTIVE");
        assertThat(view.get("backupAssetId")).isNotNull();
    }

    @Test
    void aFailedBackupBeforeReprocessingIsReportedSafelyNoJobStartsAndRetryWorks(CapturedOutput output) throws Exception {
        SpeechFixtures sx = new SpeechFixtures(fx, engine, jobs, projects, transcription, storage, json);
        UUID p = sx.audioReadyProject(12 * SpeechFixtures.MIN);
        Dialogue d = revision(p, 1, DialogueRevision.Status.ACTIVE);
        long jobsBefore = jobs.count();
        Map<String, Object> work = export(d.revision());
        Path folder = blockBackups(p);
        Map<String, Object> request = map("mode", "FAST", "language", "auto", "confirmReprocess", true);
        try {
            assertSafeFailure(send(post("/api/projects/{p}/transcribe", p), request, 500));
            assertDiagnosticLogged(output, p, d.revision());
            assertThat(jobs.count()).isEqualTo(jobsBefore);                    // no processing started
            assertThat(export(d.revision())).isEqualTo(work);
        } finally {
            Files.delete(folder);
        }
        Map<String, Object> job = send(post("/api/projects/{p}/transcribe", p), request, 202);
        assertThat(jobs.findById(UUID.fromString((String) job.get("id"))).orElseThrow().getParameters())
                .containsKey("backupAssetId");
    }
}
