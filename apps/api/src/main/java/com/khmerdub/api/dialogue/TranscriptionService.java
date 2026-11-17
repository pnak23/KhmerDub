package com.khmerdub.api.dialogue;

import com.khmerdub.api.dialogue.DialogueViews.DialogueFilter;
import com.khmerdub.api.dialogue.DialogueViews.DialoguePage;
import com.khmerdub.api.dialogue.DialogueViews.EditSummary;
import com.khmerdub.api.dialogue.DialogueViews.LineView;
import com.khmerdub.api.dialogue.DialogueViews.RevisionView;
import com.khmerdub.api.dialogue.DialogueViews.SpeakerView;
import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.job.JobViews.JobBrief;
import com.khmerdub.api.job.ProcessingJob;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.profile.ProcessingProfileRepository;
import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.project.ProjectStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting transcription and managing dialogue revisions.
 *
 * <p>Reprocessing safety: when a project already has an ACTIVE dialogue, a new run requires explicit confirmation
 * and first writes a VERIFIED backup of it (if the backup fails, no job is created). The new run produces a separate
 * READY revision; the ACTIVE one is never modified by processing and stays in use if the run fails. Switching to the
 * new revision is a separate, confirmed step that takes another verified backup; the old revision is kept read-only.
 *
 * <p>Switching back: a SUPERSEDED revision is retained with all its review work (corrections, Khmer, speaker names,
 * assignments, review history) and can be activated again the same way. Activation only changes which revision is in
 * use; it never copies or modifies dialogue rows, so switching back and forth cannot duplicate or lose review data.
 */
@Service
public class TranscriptionService {

    private final DubProjectRepository projects;
    private final JobRepository jobs;
    private final MediaAssetRepository assets;
    private final ProcessingProfileRepository profiles;
    private final DialogueRevisionRepository revisions;
    private final SpeakerRepository speakers;
    private final DialogueSegmentRepository segments;
    private final DialogueBackupService backups;
    private final JobEngine engine;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TranscriptionService(DubProjectRepository projects, JobRepository jobs, MediaAssetRepository assets,
                                ProcessingProfileRepository profiles, DialogueRevisionRepository revisions,
                                SpeakerRepository speakers, DialogueSegmentRepository segments,
                                DialogueBackupService backups, JobEngine engine, JdbcTemplate jdbc,
                                Clock clock) {
        this.projects = projects;
        this.jobs = jobs;
        this.assets = assets;
        this.profiles = profiles;
        this.revisions = revisions;
        this.speakers = speakers;
        this.segments = segments;
        this.backups = backups;
        this.engine = engine;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public JobBrief start(UUID projectId, StartTranscriptionRequest request) {
        DubProject project = projects.lockById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        if (project.isArchived()) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived. Restore it first.");
        }
        MediaAsset audio = extractedAudio(projectId);
        if (audio == null || !project.getStatus().atLeast(ProjectStatus.AUDIO_READY)) {
            throw ApiException.conflict("AUDIO_NOT_READY", "Extract the movie's audio first.");
        }
        var profile = (project.getProcessingProfileId() == null ? profiles.findFirstByDefaultProfileTrue()
                : profiles.findById(project.getProcessingProfileId())).orElseThrow();
        String mode = request.mode() != null ? request.mode() : profile.getDiarizationMode();
        String language = request.language() != null ? request.language()
                : project.getSourceLanguage() == null ? "auto" : project.getSourceLanguage();

        Map<String, Object> params = new HashMap<>();
        params.put("mode", mode);
        params.put("language", language);
        params.put("diarize", request.diarize() == null || request.diarize());
        params.put("audioAssetId", audio.getId().toString());
        var active = revisions.findFirstByProjectIdAndStatus(projectId, DialogueRevision.Status.ACTIVE);
        if (active.isPresent()) {
            if (!request.confirmReprocess()) {
                throw ApiException.conflict("REPROCESS_CONFIRMATION_REQUIRED",
                        "This project already has dialogue (revision %d, %s lines). Processing again creates a new "
                                .formatted(active.get().getRevisionNo(), active.get().getSummary().getOrDefault("lines", "?"))
                                + "revision and keeps the current one; confirm to continue. A verified backup is made first.");
            }
            MediaAsset backup = backups.backup(active.get(), "before reprocessing");
            params.put("backupAssetId", backup.getId().toString());
            params.put("previousRevisionId", active.get().getId().toString());
        }
        ProcessingJob job = engine.createJob(projectId, JobType.TRANSCRIBE, params);
        return JobBrief.of(job);
    }

    /** A finished run waiting for review, or a revision that was in use before (retained, read-only meanwhile). */
    static final Set<DialogueRevision.Status> ACTIVATABLE =
            EnumSet.of(DialogueRevision.Status.READY, DialogueRevision.Status.SUPERSEDED);

    /**
     * Put a READY or SUPERSEDED revision in use. One transaction, in this order:
     * <ol>
     *   <li>lock the project row (every review change and every reprocessing start takes the same lock first, so a
     *       switch can never interleave with an edit, an undo, a glossary apply or another switch);</li>
     *   <li>check the revision belongs to the project, is activatable and still has its dialogue;</li>
     *   <li>if a revision is in use: require confirmation, then write and VERIFY a backup of it (if the backup fails,
     *       nothing changes);</li>
     *   <li>the old one becomes SUPERSEDED (read-only, retained), the chosen one ACTIVE (editable).</li>
     * </ol>
     * Any failure rolls the whole switch back, so the previous revision stays active. The database allows at most one
     * ACTIVE revision per project.
     */
    @Transactional
    public RevisionView activate(UUID projectId, UUID revisionId, boolean confirm) {
        DubProject project = projects.lockById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        if (project.isArchived()) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived. Restore it first.");
        }
        DialogueRevision next = revisions.lockById(revisionId)
                .filter(r -> r.getProjectId().equals(projectId)).orElseThrow(() -> ApiException.notFound("Revision"));
        if (next.getStatus() == DialogueRevision.Status.ACTIVE) {
            throw ApiException.conflict("REVISION_ALREADY_ACTIVE", "Revision %d is already in use."
                    .formatted(next.getRevisionNo()));
        }
        if (!ACTIVATABLE.contains(next.getStatus())) {
            throw ApiException.conflict("REVISION_NOT_READY", "Only a finished revision or one that was in use before "
                    + "can be activated (revision %d is %s).".formatted(next.getRevisionNo(),
                    next.getStatus().name().toLowerCase()));
        }
        requireRetained(next);
        boolean reactivation = next.getStatus() == DialogueRevision.Status.SUPERSEDED;
        OffsetDateTime now = OffsetDateTime.now(clock);
        var current = revisions.findFirstByProjectIdAndStatus(projectId, DialogueRevision.Status.ACTIVE);
        if (current.isPresent()) {
            DialogueRevision old = revisions.lockById(current.get().getId()).orElseThrow();
            if (!confirm) {
                EditSummary edits = summarize(old);
                String kept = edits.hasEdits()
                        ? (" It contains your review work (%d Chinese corrections, %d Khmer lines, %d approved lines, "
                           + "%d manual speaker assignments, %d named and %d reviewed speakers, %d voices assigned), "
                           + "which stays with it and is NOT copied to revision %d.").formatted(
                                edits.linesWithChineseEdits(), edits.linesWithKhmer(), edits.approvedLines(),
                                edits.manualSpeakerAssignments(), edits.namedSpeakers(), edits.reviewedSpeakers(),
                                edits.speakersWithVoice(), next.getRevisionNo())
                        : "";
                throw ApiException.conflict("ACTIVATION_CONFIRMATION_REQUIRED", ("Switching replaces the dialogue in "
                        + "use (revision %d) with revision %d%s.%s It is kept read-only, can be made active again, and "
                        + "is backed up first; confirm to continue.").formatted(old.getRevisionNo(),
                        next.getRevisionNo(), reactivation ? " (used before; it keeps its own review work)" : "", kept));
            }
            MediaAsset backup = backups.backup(old, (reactivation ? "before re-activating revision " : "before activating revision ")
                    + next.getRevisionNo()); // verified (re-read, SHA-256, counts) or it throws and nothing changes
            old.setStatus(DialogueRevision.Status.SUPERSEDED);
            old.setSupersededAt(now);
            revisions.saveAndFlush(old); // the one-active index requires the old one to step down first
            next.setBackupAssetId(backup.getId());
        }
        next.setStatus(DialogueRevision.Status.ACTIVE);
        next.setActivatedAt(now);
        next.setSupersededAt(null);
        if (!project.getStatus().atLeast(ProjectStatus.TRANSCRIBED)) {
            project.setStatus(ProjectStatus.TRANSCRIBED);
        }
        return RevisionView.of(revisions.saveAndFlush(next));
    }

    /** A revision that says it has lines but has none left cannot be put back in use. */
    private void requireRetained(DialogueRevision r) {
        Object expected = r.getSummary().get("lines");
        if (expected instanceof Number n && n.longValue() > 0 && segments.countByRevisionId(r.getId()) == 0) {
            throw ApiException.conflict("REVISION_DATA_MISSING", "Revision %d no longer has its dialogue and cannot be "
                    + "activated.".formatted(r.getRevisionNo()));
        }
    }

    @Transactional(readOnly = true)
    public List<RevisionView> revisions(UUID projectId) {
        requireProject(projectId);
        return revisions.findByProjectIdOrderByRevisionNoDesc(projectId).stream().map(RevisionView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<SpeakerView> speakers(UUID projectId, UUID revisionId) {
        DialogueRevision r = revision(projectId, revisionId);
        return speakerViews(r, speakers.findByRevisionIdOrderByDisplayOrderAsc(r.getId()));
    }

    /** Current state of the given speakers of the project (for the result of a review change). */
    @Transactional(readOnly = true)
    public List<SpeakerView> speakersById(UUID projectId, Collection<UUID> ids) {
        List<Speaker> found = speakers.findAllById(ids).stream().filter(s -> s.getProjectId().equals(projectId))
                .sorted(Comparator.comparingInt(Speaker::getDisplayOrder).thenComparing(Speaker::getSpeakerCode))
                .toList();
        if (found.isEmpty()) {
            return List.of();
        }
        DialogueRevision r = revisions.findById(found.getFirst().getRevisionId()).orElseThrow();
        return speakerViews(r, found);
    }

    /** Current state of the given lines of the project, in dialogue order. */
    @Transactional(readOnly = true)
    public List<LineView> linesById(UUID projectId, Collection<UUID> ids) {
        return segments.findAllById(ids).stream().filter(s -> s.getProjectId().equals(projectId))
                .sorted(Comparator.comparingInt(DialogueSegment::getSequenceNo)).map(LineView::of).toList();
    }

    private List<SpeakerView> speakerViews(DialogueRevision r, List<Speaker> list) {
        List<MediaAsset> samples = assets.findByProjectIdAndTypeOrderByCreatedAtAsc(r.getProjectId(),
                        MediaAssetType.SPEAKER_SAMPLE).stream()
                .filter(a -> r.getSourceJobId() != null
                        && r.getSourceJobId().toString().equals(a.getMetadata().get("jobId")))
                .toList();
        return list.stream().map(s -> SpeakerView.of(s, samples.stream()
                .filter(a -> s.getSpeakerCode().equals(a.getMetadata().get("speaker")))
                .map(MediaAsset::getId).toList())).toList();
    }

    static final Set<String> FLAGS = Set.of("lowConfidence", "mixedSpeakers", "firstWordRetimed");
    static final Set<String> LINE_REVIEW = Set.of("PENDING", "NEEDS_REVIEW", "APPROVED", "REJECTED");

    @Transactional(readOnly = true)
    public DialoguePage dialogue(UUID projectId, UUID revisionId, int fromSeq, int limit, DialogueFilter filter) {
        DialogueRevision r = revision(projectId, revisionId);
        int size = Math.max(1, Math.min(limit, 1000));
        int from = Math.max(0, fromSeq);
        if (filter == null || !filter.any()) {
            List<LineView> lines = segments.findByRevisionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(r.getId(),
                    from, Limit.of(size)).stream().map(LineView::of).toList();
            return new DialoguePage(r.getId(), r.getStatus().name(), segments.countByRevisionId(r.getId()), from, lines);
        }
        StringBuilder where = new StringBuilder("revision_id = ?");
        List<Object> args = new ArrayList<>(List.of(r.getId()));
        if (filter.speakerId() != null) {
            where.append(" AND speaker_id = ?");
            args.add(filter.speakerId());
        }
        if (filter.reviewStatus() != null) {
            if (!LINE_REVIEW.contains(filter.reviewStatus())) {
                throw ApiException.badRequest("INVALID_FILTER", "reviewStatus must be one of " + LINE_REVIEW);
            }
            where.append(" AND review_status = ?");
            args.add(filter.reviewStatus());
        }
        if (filter.flag() != null) {
            if ("any".equals(filter.flag())) {
                where.append(" AND flags <> '{}'::jsonb");
            } else if (FLAGS.contains(filter.flag())) {
                where.append(" AND (flags ->> ?) = 'true'");
                args.add(filter.flag());
            } else {
                throw ApiException.badRequest("INVALID_FILTER", "flag must be 'any' or one of " + FLAGS);
            }
        }
        if (filter.text() != null && !filter.text().isBlank()) {
            if (filter.text().length() > 200) {
                throw ApiException.badRequest("INVALID_FILTER", "Search text is limited to 200 characters.");
            }
            String like = "%" + escapeLike(filter.text()) + "%";
            where.append(" AND (source_text ILIKE ? OR source_text_edited ILIKE ? OR edited_text ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM dialogue_segments WHERE " + where, Long.class,
                args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(from);
        pageArgs.add(size);
        List<UUID> ids = jdbc.queryForList("SELECT id FROM dialogue_segments WHERE " + where
                + " AND sequence_no >= ? ORDER BY sequence_no LIMIT ?", UUID.class, pageArgs.toArray());
        List<LineView> lines = segments.findAllById(ids).stream()
                .sorted(Comparator.comparingInt(DialogueSegment::getSequenceNo)).map(LineView::of).toList();
        return new DialoguePage(r.getId(), r.getStatus().name(), total == null ? 0 : total, from, lines);
    }

    /** LIKE pattern for literal text: the default escape character is the backslash. */
    static String escapeLike(String text) {
        StringBuilder b = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c == '\\' || c == '%' || c == '_') {
                b.append('\\');
            }
            b.append(c);
        }
        return b.toString();
    }

    @Transactional(readOnly = true)
    public EditSummary editSummary(UUID projectId, UUID revisionId) {
        return summarize(revision(projectId, revisionId));
    }

    private EditSummary summarize(DialogueRevision r) {
        Map<String, Object> l = jdbc.queryForMap("""
                SELECT count(*) AS lines,
                       count(*) FILTER (WHERE source_text_edited IS NOT NULL) AS zh,
                       count(*) FILTER (WHERE edited_text IS NOT NULL AND btrim(edited_text) <> '') AS km,
                       count(*) FILTER (WHERE review_status = 'APPROVED') AS approved,
                       count(*) FILTER (WHERE speaker_assignment = 'MANUAL') AS manual
                  FROM dialogue_segments WHERE revision_id = ?""", r.getId());
        Map<String, Object> k = jdbc.queryForMap("""
                SELECT count(*) AS speakers,
                       count(*) FILTER (WHERE character_name IS NOT NULL AND btrim(character_name) <> '') AS named,
                       count(*) FILTER (WHERE review_status = 'REVIEWED') AS reviewed,
                       count(*) FILTER (WHERE voice_profile_id IS NOT NULL) AS voiced
                  FROM speakers WHERE revision_id = ?""", r.getId());
        Long ops = jdbc.queryForObject(
                "SELECT count(*) FROM review_operations WHERE revision_id = ? AND undone_at IS NULL", Long.class,
                r.getId());
        return new EditSummary(r.getId(), r.getRevisionNo(), r.getStatus().name(), n(l, "lines"), n(l, "zh"),
                n(l, "km"), n(l, "approved"), n(l, "manual"), n(k, "speakers"), n(k, "named"), n(k, "reviewed"),
                n(k, "voiced"), ops == null ? 0 : ops);
    }

    private static long n(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    /** The given revision, or the ACTIVE one when no id is given. */
    private DialogueRevision revision(UUID projectId, UUID revisionId) {
        requireProject(projectId);
        if (revisionId != null) {
            return revisions.findById(revisionId).filter(r -> r.getProjectId().equals(projectId))
                    .orElseThrow(() -> ApiException.notFound("Revision"));
        }
        return revisions.findFirstByProjectIdAndStatus(projectId, DialogueRevision.Status.ACTIVE)
                .orElseThrow(() -> ApiException.notFound("Active dialogue"));
    }

    private void requireProject(UUID projectId) {
        if (!projects.existsById(projectId)) {
            throw ApiException.notFound("Project");
        }
    }

    private MediaAsset extractedAudio(UUID projectId) {
        return jobs.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .filter(j -> j.getType() == JobType.EXTRACT_AUDIO && j.getStatus() == JobStatus.COMPLETED
                        && j.getResultAssetId() != null)
                .findFirst().flatMap(j -> assets.findById(j.getResultAssetId())).orElse(null);
    }
}
