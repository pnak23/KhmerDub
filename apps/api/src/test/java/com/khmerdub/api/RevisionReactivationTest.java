package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.khmerdub.api.dialogue.DialogueRevision;
import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Switching back to a retained (SUPERSEDED) revision: it keeps all its review work, becomes editable again, the
 * previous one becomes read-only; a verified backup comes first; failures and concurrent edits change nothing else.
 */
@IntegrationTest
class RevisionReactivationTest extends ReviewTestSupport {

    @Autowired JobRepository jobs;
    @Autowired TranscriptionService transcription;

    private Map<String, Object> activate(UUID project, UUID revision, boolean confirm, int expected) throws Exception {
        return send(post("/api/projects/{p}/dialogue-revisions/{r}/activate", project, revision), map("confirm", confirm), expected);
    }

    private String status(UUID revision) {
        return jdbc.queryForObject("SELECT status FROM dialogue_revisions WHERE id = ?", String.class, revision);
    }

    private long activeCount(UUID project) {
        return jdbc.queryForObject("SELECT count(*) FROM dialogue_revisions WHERE project_id = ? AND status = 'ACTIVE'",
                Long.class, project);
    }

    private long backups(UUID project) {
        return jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE project_id = ? AND asset_type = 'DIALOGUE_BACKUP'",
                Long.class, project);
    }

    private Map<String, Object> editKhmer(Dialogue d, UUID line, String text, int expected) throws Exception {
        return send(patch("/api/projects/{p}/segments/{s}", d.project(), line), map("version", lineVersion(line), "editedText", text),
                expected);
    }

    /** Review work of every kind on a revision: line text + status, speaker name/review/voice, manual assignment. */
    private void review(Dialogue d) throws Exception {
        send(patch("/api/projects/{p}/segments/{s}", d.project(), d.lines().get(0)),
                map("version", lineVersion(d.lines().get(0)), "sourceTextEdited", "你好，我是", "editedText", "សួស្តី",
                        "reviewStatus", "APPROVED"), 200);
        send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk1()),
                map("version", speakerVersion(d.spk1()), "characterName", "Pei", "reviewStatus", "REVIEWED"), 200);
        send(post("/api/projects/{p}/segments/reassign", d.project()), map("speakerId", d.spk1(),
                "segments", List.of(ref(d.lines().get(1), lineVersion(d.lines().get(1))))), 200);
        Map<String, Object> voice = send(post("/api/voice-profiles"), map("displayName", "V-" + UUID.randomUUID(),
                "voiceCategory", "ADULT_FEMALE"), 201);
        send(patch("/api/projects/{p}/speakers/{s}", d.project(), d.spk2()),
                map("version", speakerVersion(d.spk2()), "voiceProfileId", voice.get("id")), 200);
    }

    @Test
    void anOlderRevisionComesBackWithAllItsReviewWorkAndBecomesEditableAgain() throws Exception {
        Dialogue a = activeDialogue();
        Dialogue b = revision(a.project(), 2, DialogueRevision.Status.READY);
        review(a);
        Map<String, Object> aWork = export(a.revision());
        long aOps = operations(a.revision());

        assertThat(activate(a.project(), b.revision(), false, 409)).containsEntry("code", "ACTIVATION_CONFIRMATION_REQUIRED");
        assertThat(status(a.revision())).isEqualTo("ACTIVE");
        activate(a.project(), b.revision(), true, 200);
        assertThat(status(a.revision())).isEqualTo("SUPERSEDED");
        assertThat(status(b.revision())).isEqualTo("ACTIVE");
        // the previous revision is read-only now (edits and undo)
        assertThat(editKhmer(a, a.lines().get(2), "x", 409)).containsEntry("code", "REVISION_READ_ONLY");
        String aLatest = jdbc.queryForObject("SELECT id::text FROM review_operations WHERE revision_id = ? ORDER BY seq DESC LIMIT 1",
                String.class, a.revision());
        assertThat(send(post("/api/projects/{p}/review-operations/{o}/undo", a.project(), aLatest), null, 409))
                .containsEntry("code", "REVISION_READ_ONLY");
        editKhmer(b, b.lines().get(0), "revision 2 work", 200);

        // switch back: confirmation, verified backup of revision 2, revision 1 exactly as it was
        Map<String, Object> err = activate(a.project(), a.revision(), false, 409);
        assertThat(err).containsEntry("code", "ACTIVATION_CONFIRMATION_REQUIRED");
        assertThat((String) err.get("detail")).contains("used before");
        Map<String, Object> view = activate(a.project(), a.revision(), true, 200);
        assertThat(view).containsEntry("status", "ACTIVE").containsEntry("supersededAt", null);
        assertThat(status(b.revision())).isEqualTo("SUPERSEDED");
        MediaAsset backup = assets.findById(UUID.fromString((String) view.get("backupAssetId"))).orElseThrow();
        assertThat(backup.getType()).isEqualTo(MediaAssetType.DIALOGUE_BACKUP);
        assertThat(backup.getMetadata()).containsEntry("revisionId", b.revision().toString()).containsEntry("verified", true);
        assertThat(export(a.revision())).isEqualTo(aWork);                    // nothing lost, nothing copied in
        assertThat(operations(a.revision())).isEqualTo(aOps);
        assertThat(lineRow(a.lines().get(0)).get("edited_text")).isEqualTo("សួស្តី");
        assertThat(lineRow(b.lines().get(0)).get("edited_text")).isEqualTo("revision 2 work"); // stays with revision 2

        // editing resumes on revision 1, and its own undo history continues (last-first)
        Map<String, Object> resumed = editKhmer(a, a.lines().get(2), "resumed", 200);
        send(post("/api/projects/{p}/review-operations/{o}/undo", a.project(), operation(resumed).get("id")), null, 200);
        assertThat(lineRow(a.lines().get(2)).get("edited_text")).isNull();
        send(post("/api/projects/{p}/review-operations/{o}/undo", a.project(), aLatest), null, 200); // the voice assignment
        assertThat(speakerRow(a.spk2()).get("voice_profile_id")).isNull();
        assertThat(editKhmer(b, b.lines().get(1), "x", 409)).containsEntry("code", "REVISION_READ_ONLY");
        assertThat(activeCount(a.project())).isEqualTo(1);
    }

    @Test
    void aFailedBackupPreventsTheSwitchAndLeavesTheActiveRevisionInUse() throws Exception {
        Dialogue a = activeDialogue();
        Dialogue b = revision(a.project(), 2, DialogueRevision.Status.READY);
        review(a);
        Map<String, Object> aWork = export(a.revision());
        // the backup folder cannot be created (a file is in its place)
        Path folder = storage.resolve(StorageService.projectDir(a.project()) + "/dialogue/backups");
        Files.createDirectories(folder.getParent());
        if (Files.isDirectory(folder)) {
            try (var s = Files.list(folder)) {
                assertThat(s.count()).isZero();
            }
            Files.delete(folder);
        }
        Files.writeString(folder, "blocks the backup folder");
        try {
            assertThat(activate(a.project(), b.revision(), true, 500)).containsEntry("code", "BACKUP_FAILED");
            assertThat(status(a.revision())).isEqualTo("ACTIVE");
            assertThat(status(b.revision())).isEqualTo("READY");
            assertThat(backups(a.project())).isZero();
            assertThat(export(a.revision())).isEqualTo(aWork);
            editKhmer(a, a.lines().get(3), "still editable", 200);
            aWork = export(a.revision());
        } finally {
            Files.delete(folder);
        }
        // works once the backup can be written; then the same protection holds when switching BACK
        activate(a.project(), b.revision(), true, 200);
        Path held = folder.resolveSibling("backups-held");
        Files.move(folder, held);                       // keep the written backup aside (nothing is deleted)
        Files.writeString(folder, "blocks the backup folder again");
        try {
            assertThat(activate(a.project(), a.revision(), true, 500)).containsEntry("code", "BACKUP_FAILED");
            assertThat(status(b.revision())).isEqualTo("ACTIVE");
            assertThat(status(a.revision())).isEqualTo("SUPERSEDED");
            assertThat(backups(a.project())).isEqualTo(1);
        } finally {
            Files.delete(folder);
            Files.move(held, folder);
        }
        activate(a.project(), a.revision(), true, 200);
        assertThat(export(a.revision())).isEqualTo(aWork);
    }

    @Test
    void onlyARetainedReadyOrSupersededRevisionOfThisProjectCanBeActivated() throws Exception {
        Dialogue a = activeDialogue();
        Dialogue failed = revision(a.project(), 2, DialogueRevision.Status.FAILED);
        Dialogue building = revision(a.project(), 3, DialogueRevision.Status.BUILDING);
        Dialogue other = activeDialogue();
        Dialogue otherReady = revision(other.project(), 2, DialogueRevision.Status.READY);

        activate(a.project(), UUID.randomUUID(), true, 404);
        activate(a.project(), otherReady.revision(), true, 404);                 // another project's revision
        assertThat(status(otherReady.revision())).isEqualTo("READY");
        assertThat(activate(a.project(), failed.revision(), true, 409)).containsEntry("code", "REVISION_NOT_READY");
        assertThat(activate(a.project(), building.revision(), true, 409)).containsEntry("code", "REVISION_NOT_READY");
        assertThat(activate(a.project(), a.revision(), true, 409)).containsEntry("code", "REVISION_ALREADY_ACTIVE");
        // a superseded revision whose dialogue is gone is not "retained"
        Dialogue gone = revision(a.project(), 4, DialogueRevision.Status.SUPERSEDED);
        jdbc.update("UPDATE dialogue_revisions SET summary = '{\"lines\": 4}' WHERE id = ?", gone.revision());
        jdbc.update("DELETE FROM dialogue_segments WHERE revision_id = ?", gone.revision());
        assertThat(activate(a.project(), gone.revision(), true, 409)).containsEntry("code", "REVISION_DATA_MISSING");
        // archived projects cannot switch
        Dialogue ready = revision(a.project(), 5, DialogueRevision.Status.READY);
        send(post("/api/projects/{id}/archive", a.project()), null, 200);
        assertThat(activate(a.project(), ready.revision(), true, 409)).containsEntry("code", "PROJECT_ARCHIVED");
        assertThat(status(a.revision())).isEqualTo("ACTIVE");
        assertThat(backups(a.project())).isZero();
    }

    @Test
    void concurrentEditsAndSwitchesNeverCorruptRevisionState() throws Exception {
        Dialogue a = activeDialogue();
        Dialogue b = revision(a.project(), 2, DialogueRevision.Status.READY);
        UUID line = a.lines().get(0);
        Map<String, Object> bBefore = export(b.revision());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Integer> editStatus = Collections.synchronizedList(new ArrayList<>());
        List<Integer> switchStatus = Collections.synchronizedList(new ArrayList<>());
        Future<?> editor = pool.submit(() -> {
            go.await();
            for (int i = 0; i < 25; i++) {
                editStatus.add(mvc.perform(patch("/api/projects/{p}/segments/{s}", a.project(), line)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(map("version", lineVersion(line), "editedText", "edit " + i))))
                        .andReturn().getResponse().getStatus());
            }
            return null;
        });
        Future<?> switcher = pool.submit(() -> {
            go.await();
            for (int i = 0; i < 6; i++) {
                UUID target = i % 2 == 0 ? b.revision() : a.revision();
                switchStatus.add(mvc.perform(post("/api/projects/{p}/dialogue-revisions/{r}/activate", a.project(), target)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"confirm\":true}"))
                        .andReturn().getResponse().getStatus());
            }
            return null;
        });
        go.countDown();
        editor.get(120, TimeUnit.SECONDS);
        switcher.get(120, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(switchStatus).containsOnly(200);
        assertThat(editStatus).allMatch(s -> s == 200 || s == 409);              // never a server error
        long applied = editStatus.stream().filter(s -> s == 200).count();
        // every accepted edit is exactly one logged change of revision 1; refused ones left no trace
        assertThat(operations(a.revision())).isEqualTo(applied);
        assertThat(lineVersion(line)).isEqualTo(applied);
        assertThat(operations(b.revision())).isZero();
        assertThat(export(b.revision())).isEqualTo(bBefore);                     // nothing leaked into revision 2
        assertThat(activeCount(a.project())).isEqualTo(1);
        assertThat(status(a.revision())).isEqualTo("ACTIVE");                   // 6 switches: ends on revision 1
        assertThat(backups(a.project())).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media_assets WHERE project_id = ? AND asset_type = 'DIALOGUE_BACKUP' "
                + "AND (metadata ->> 'verified')::boolean", Long.class, a.project())).isEqualTo(6);
    }

    @Test
    void repeatedSwitchingNeitherDuplicatesNorLosesReviewData() throws Exception {
        Dialogue a = activeDialogue();
        Dialogue b = revision(a.project(), 2, DialogueRevision.Status.READY);
        Map<UUID, Map<UUID, String>> expected = new LinkedHashMap<>(Map.of(a.revision(), new LinkedHashMap<>(),
                b.revision(), new LinkedHashMap<>()));
        Dialogue active = a;
        for (int round = 0; round < 8; round++) {
            UUID line = active.lines().get(round % 4);
            String text = "round " + round + " on revision " + (active == a ? 1 : 2);
            editKhmer(active, line, text, 200);
            expected.get(active.revision()).put(line, text);
            Dialogue next = active == a ? b : a;
            activate(a.project(), next.revision(), true, 200);
            assertThat(activeCount(a.project())).isEqualTo(1);
            active = next;
        }
        for (Dialogue d : List.of(a, b)) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dialogue_segments WHERE revision_id = ?", Long.class, d.revision()))
                    .isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM speakers WHERE revision_id = ?", Long.class, d.revision()))
                    .isEqualTo(2);
            assertThat(operations(d.revision())).isEqualTo(4);
            for (UUID line : d.lines()) {
                assertThat(lineRow(line).get("edited_text")).isEqualTo(expected.get(d.revision()).get(line));
            }
        }
        assertThat(backups(a.project())).isEqualTo(8);
        assertThat(status(a.revision())).isEqualTo("ACTIVE");
    }

    @Test
    void reprocessingSafetyAndEditGuardsStillHoldAfterSwitchingBack() throws Exception {
        SpeechFixtures sx = new SpeechFixtures(fx, engine, jobs, projects, transcription, storage, json);
        UUID p = sx.audioReadyProject(12 * SpeechFixtures.MIN);
        assertThat(sx.run(sx.start(p, new StartTranscriptionRequest(null, null, null, false)), null, "-v1"))
                .isEqualTo(JobStatus.COMPLETED);
        UUID rev1 = revisions.findFirstByProjectIdAndStatus(p, DialogueRevision.Status.ACTIVE).orElseThrow().getId();
        assertThat(sx.run(sx.start(p, new StartTranscriptionRequest(null, null, null, true)), null, "-v2"))
                .isEqualTo(JobStatus.COMPLETED);
        UUID rev2 = revisions.findByProjectIdOrderByRevisionNoDesc(p).getFirst().getId();
        UUID line1 = jdbc.queryForObject("SELECT id FROM dialogue_segments WHERE revision_id = ? AND sequence_no = 0", UUID.class, rev1);
        send(patch("/api/projects/{p}/segments/{s}", p, line1), map("version", lineVersion(line1), "editedText", "rev1 work"), 200);
        activate(p, rev2, true, 200);
        activate(p, rev1, true, 200);                                            // back to the reviewed revision
        Map<String, Object> rev1Work = export(rev1);

        // reprocessing still requires confirmation and backs up the revision in use (now revision 1 again)
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sx.start(p, new StartTranscriptionRequest(null, null, null, false)))
                .isInstanceOf(ApiException.class).hasMessageContaining("confirm");
        UUID job3 = sx.start(p, new StartTranscriptionRequest(null, null, null, true));
        String backupId = (String) jobs.findById(job3).orElseThrow().getParameters().get("backupAssetId");
        MediaAsset backup = assets.findById(UUID.fromString(backupId)).orElseThrow();
        assertThat(backup.getMetadata()).containsEntry("revisionId", rev1.toString()).containsEntry("verified", true);
        assertThat(sx.run(job3, null, "-v3")).isEqualTo(JobStatus.COMPLETED);
        // processing never touched the revision in use; the new run waits as READY
        assertThat(status(rev1)).isEqualTo("ACTIVE");
        assertThat(export(rev1)).isEqualTo(rev1Work);
        assertThat(revisions.findByProjectIdOrderByRevisionNoDesc(p).getFirst().getStatus()).isEqualTo(DialogueRevision.Status.READY);
        // edit guards: the superseded revision 2 stays read-only
        UUID line2 = jdbc.queryForObject("SELECT id FROM dialogue_segments WHERE revision_id = ? AND sequence_no = 0", UUID.class, rev2);
        assertThat(send(patch("/api/projects/{p}/segments/{s}", p, line2), map("version", lineVersion(line2), "editedText", "x"), 409))
                .containsEntry("code", "REVISION_READ_ONLY");
    }
}
