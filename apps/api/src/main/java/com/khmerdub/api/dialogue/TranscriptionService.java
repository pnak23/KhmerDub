package com.khmerdub.api.dialogue;

import com.khmerdub.api.dialogue.DialogueViews.DialoguePage;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting transcription and managing dialogue revisions.
 *
 * <p>Reprocessing safety: when a project already has an ACTIVE dialogue, a new run requires explicit confirmation
 * and first writes a VERIFIED backup of it (if the backup fails, no job is created). The new run produces a separate
 * READY revision; the ACTIVE one is never modified by processing and stays in use if the run fails. Switching to the
 * new revision is a separate, confirmed step that takes another verified backup; the old revision is kept read-only.
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
    private final Clock clock;

    public TranscriptionService(DubProjectRepository projects, JobRepository jobs, MediaAssetRepository assets,
                                ProcessingProfileRepository profiles, DialogueRevisionRepository revisions,
                                SpeakerRepository speakers, DialogueSegmentRepository segments,
                                DialogueBackupService backups, JobEngine engine, Clock clock) {
        this.projects = projects;
        this.jobs = jobs;
        this.assets = assets;
        this.profiles = profiles;
        this.revisions = revisions;
        this.speakers = speakers;
        this.segments = segments;
        this.backups = backups;
        this.engine = engine;
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

    @Transactional
    public RevisionView activate(UUID projectId, UUID revisionId, boolean confirm) {
        DubProject project = projects.lockById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        DialogueRevision next = revisions.lockById(revisionId)
                .filter(r -> r.getProjectId().equals(projectId)).orElseThrow(() -> ApiException.notFound("Revision"));
        if (next.getStatus() != DialogueRevision.Status.READY) {
            throw ApiException.conflict("REVISION_NOT_READY", "Only a READY revision can be activated (this one is %s)."
                    .formatted(next.getStatus().name().toLowerCase()));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        var current = revisions.findFirstByProjectIdAndStatus(projectId, DialogueRevision.Status.ACTIVE);
        if (current.isPresent()) {
            if (!confirm) {
                throw ApiException.conflict("ACTIVATION_CONFIRMATION_REQUIRED", "Switching replaces the dialogue in "
                        + "use (revision %d). It is kept read-only and backed up first; confirm to continue."
                        .formatted(current.get().getRevisionNo()));
            }
            MediaAsset backup = backups.backup(current.get(), "before activating revision " + next.getRevisionNo());
            DialogueRevision old = revisions.lockById(current.get().getId()).orElseThrow();
            old.setStatus(DialogueRevision.Status.SUPERSEDED);
            old.setSupersededAt(now);
            revisions.saveAndFlush(old); // the one-active index requires the old one to step down first
            next.setBackupAssetId(backup.getId());
        }
        next.setStatus(DialogueRevision.Status.ACTIVE);
        next.setActivatedAt(now);
        if (!project.getStatus().atLeast(ProjectStatus.TRANSCRIBED)) {
            project.setStatus(ProjectStatus.TRANSCRIBED);
        }
        return RevisionView.of(revisions.saveAndFlush(next));
    }

    @Transactional(readOnly = true)
    public List<RevisionView> revisions(UUID projectId) {
        requireProject(projectId);
        return revisions.findByProjectIdOrderByRevisionNoDesc(projectId).stream().map(RevisionView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<SpeakerView> speakers(UUID projectId, UUID revisionId) {
        DialogueRevision r = revision(projectId, revisionId);
        List<MediaAsset> samples = assets.findByProjectIdAndTypeOrderByCreatedAtAsc(projectId, MediaAssetType.SPEAKER_SAMPLE)
                .stream().filter(a -> r.getSourceJobId() != null
                        && r.getSourceJobId().toString().equals(a.getMetadata().get("jobId"))).toList();
        return speakers.findByRevisionIdOrderByDisplayOrderAsc(r.getId()).stream()
                .map(s -> new SpeakerView(s.getId(), s.getSpeakerCode(), s.getCharacterName(), s.getSpeechMs(),
                        s.getSegmentCount(), s.getDisplayOrder(), s.getReviewStatus(), s.getVoiceProfileId(),
                        samples.stream().filter(a -> s.getSpeakerCode().equals(a.getMetadata().get("speaker")))
                                .map(MediaAsset::getId).toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public DialoguePage dialogue(UUID projectId, UUID revisionId, int fromSeq, int limit) {
        DialogueRevision r = revision(projectId, revisionId);
        int size = Math.max(1, Math.min(limit, 1000));
        List<LineView> lines = segments.findByRevisionIdAndSequenceNoGreaterThanEqualOrderBySequenceNoAsc(r.getId(),
                Math.max(0, fromSeq), Limit.of(size)).stream().map(LineView::of).toList();
        return new DialoguePage(r.getId(), r.getStatus().name(), segments.countByRevisionId(r.getId()), fromSeq, lines);
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
