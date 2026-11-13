package com.khmerdub.api.project;

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
import com.khmerdub.api.project.ProjectViews.AssetView;
import com.khmerdub.api.project.ProjectViews.CreateProjectRequest;
import com.khmerdub.api.project.ProjectViews.ProjectDetail;
import com.khmerdub.api.project.ProjectViews.ProjectSummary;
import com.khmerdub.api.project.ProjectViews.StartExtractionRequest;
import com.khmerdub.api.project.ProjectViews.UpdateProjectRequest;
import com.khmerdub.api.storage.StorageService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final DubProjectRepository projects;
    private final MediaAssetRepository assets;
    private final JobRepository jobs;
    private final ProcessingProfileRepository profiles;
    private final JobEngine engine;
    private final StorageService storage;

    public ProjectService(DubProjectRepository projects, MediaAssetRepository assets, JobRepository jobs,
                          ProcessingProfileRepository profiles, JobEngine engine, StorageService storage) {
        this.projects = projects;
        this.assets = assets;
        this.jobs = jobs;
        this.profiles = profiles;
        this.engine = engine;
        this.storage = storage;
    }

    @Transactional
    public ProjectDetail create(CreateProjectRequest request) {
        DubProject project = new DubProject(request.name().trim());
        project.setSourceLanguage(language(request.sourceLanguage()));
        profiles.findFirstByDefaultProfileTrue().ifPresent(p -> project.setProcessingProfileId(p.getId()));
        DubProject saved = projects.saveAndFlush(project);
        storage.createProjectLayout(saved.getId());
        return detail(saved.getId());
    }

    @Transactional(readOnly = true)
    public List<ProjectSummary> list(boolean includeArchived) {
        List<DubProject> all = includeArchived ? projects.findAllByOrderByUpdatedAtDesc()
                : projects.findByArchivedAtIsNullOrderByUpdatedAtDesc();
        return all.stream().map(this::summary).toList();
    }

    @Transactional(readOnly = true)
    public ProjectSummary summary(UUID projectId) {
        return summary(find(projectId));
    }

    @Transactional(readOnly = true)
    public ProjectDetail detail(UUID projectId) {
        DubProject p = find(projectId);
        List<ProcessingJob> projectJobs = jobs.findByProjectIdOrderByCreatedAtDesc(projectId);
        MediaAsset original = assets.findFirstByProjectIdAndType(projectId, MediaAssetType.ORIGINAL_VIDEO).orElse(null);
        MediaAsset audio = projectJobs.stream()
                .filter(j -> j.getType() == JobType.EXTRACT_AUDIO && j.getStatus() == JobStatus.COMPLETED
                        && j.getResultAssetId() != null)
                .findFirst().flatMap(j -> assets.findById(j.getResultAssetId())).orElse(null);
        return new ProjectDetail(summary(p), p.getTargetLanguage(), p.getTranslationStyle(),
                p.getSourceAudioStreamIndex(), p.getProcessingProfileId(), AssetView.of(original), AssetView.of(audio),
                projectJobs.stream().map(JobBrief::of).toList());
    }

    @Transactional
    public ProjectDetail update(UUID projectId, UpdateProjectRequest request) {
        DubProject p = lockActive(projectId);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw ApiException.badRequest("INVALID_NAME", "Enter a project name.");
            }
            p.setName(request.name().trim());
        }
        if (request.sourceLanguage() != null) {
            p.setSourceLanguage(language(request.sourceLanguage()));
        }
        if (request.sourceAudioStreamIndex() != null) {
            requireAudioStream(projectId, request.sourceAudioStreamIndex());
            p.setSourceAudioStreamIndex(request.sourceAudioStreamIndex());
        }
        return detail(projectId);
    }

    @Transactional
    public ProjectDetail setArchived(UUID projectId, boolean archived) {
        DubProject p = projects.lockById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        if (archived && jobs.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .anyMatch(j -> JobStatus.ACTIVE.contains(j.getStatus()))) {
            throw ApiException.conflict("PROJECT_BUSY", "Cancel or finish the project's active job before archiving.");
        }
        p.setArchivedAt(archived ? OffsetDateTime.now() : null);
        return detail(projectId);
    }

    /** Start audio extraction (after a successful inspection). */
    @Transactional
    public JobBrief startExtraction(UUID projectId, StartExtractionRequest request) {
        DubProject p = lockActive(projectId);
        if (!p.getStatus().atLeast(ProjectStatus.IMPORTED) || p.getDurationMs() == null) {
            throw ApiException.conflict("NOT_INSPECTED", "The movie must be imported and inspected first.");
        }
        int stream = request.audioStreamIndex() != null ? request.audioStreamIndex()
                : p.getSourceAudioStreamIndex() == null ? -1 : p.getSourceAudioStreamIndex();
        requireAudioStream(projectId, stream);
        p.setSourceAudioStreamIndex(stream);
        if (request.sourceLanguage() != null) {
            p.setSourceLanguage(language(request.sourceLanguage()));
        }
        ProcessingJob job = engine.createJob(projectId, JobType.EXTRACT_AUDIO, Map.of("audioStreamIndex", stream));
        return JobBrief.of(job);
    }

    // ---------------------------------------------------------------------------------------------------

    DubProject find(UUID projectId) {
        return projects.findById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
    }

    private DubProject lockActive(UUID projectId) {
        DubProject p = projects.lockById(projectId).orElseThrow(() -> ApiException.notFound("Project"));
        if (p.isArchived()) {
            throw ApiException.conflict("PROJECT_ARCHIVED", "The project is archived. Restore it first.");
        }
        return p;
    }

    private ProjectSummary summary(DubProject p) {
        List<ProcessingJob> projectJobs = jobs.findByProjectIdOrderByCreatedAtDesc(p.getId());
        JobBrief active = projectJobs.stream().filter(j -> JobStatus.ACTIVE.contains(j.getStatus()))
                .findFirst().map(JobBrief::of).orElse(null);
        JobBrief last = projectJobs.stream().findFirst().map(JobBrief::of).orElse(null);
        MediaAsset original = assets.findFirstByProjectIdAndType(p.getId(), MediaAssetType.ORIGINAL_VIDEO).orElse(null);
        return new ProjectSummary(p.getId(), p.getName(), p.getStatus(), p.getDurationMs(),
                p.getSourceLanguage() == null ? "auto" : p.getSourceLanguage(), p.isArchived(),
                original == null ? null : original.getFilename(), original == null ? null : original.getFileSizeBytes(),
                active, last, p.getCreatedAt(), p.getUpdatedAt());
    }

    @SuppressWarnings("unchecked")
    private void requireAudioStream(UUID projectId, int index) {
        MediaAsset original = assets.findFirstByProjectIdAndType(projectId, MediaAssetType.ORIGINAL_VIDEO)
                .orElseThrow(() -> ApiException.conflict("NO_MOVIE", "Upload a movie first."));
        Map<String, Object> probe = (Map<String, Object>) original.getMetadata().get("probe");
        List<Map<String, Object>> audio = probe == null ? List.of()
                : (List<Map<String, Object>>) probe.getOrDefault("audioStreams", List.of());
        boolean exists = audio.stream().anyMatch(s -> s.get("index") instanceof Number n && n.intValue() == index);
        if (!exists) {
            throw ApiException.badRequest("INVALID_AUDIO_STREAM", "Audio stream %d does not exist in this movie.".formatted(index));
        }
    }

    private static String language(String value) {
        return value == null || value.equals("auto") ? null : value;
    }
}
