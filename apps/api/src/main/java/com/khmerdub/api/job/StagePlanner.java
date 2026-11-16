package com.khmerdub.api.job;

import com.khmerdub.api.job.EngineExceptions.InvalidOutputException;
import com.khmerdub.api.job.EngineExceptions.JobCannotContinueException;
import com.khmerdub.api.job.EngineExceptions.PlanningException;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.JobEnums.Stage;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.Output;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.profile.ProcessingProfile;
import com.khmerdub.api.profile.ProcessingProfileRepository;
import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.project.DubProjectRepository;
import com.khmerdub.api.project.ProjectStatus;
import com.khmerdub.api.storage.MediaFiles;
import com.khmerdub.api.storage.StorageService;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stage-specific knowledge: how a stage is split into chunks, what a worker receives, how a result is
 * verified and published, and how stages weigh in job progress. Speech stages (M3b) are delegated to
 * {@link SpeechStagePlanner}.
 */
@Component
public class StagePlanner {

    public static final int ASR_SAMPLE_RATE = 16_000;
    static final Set<Stage> SPEECH_STAGES = EnumSet.of(Stage.SPEECH_MAP, Stage.DETECT_LANGUAGE, Stage.TRANSCRIBE,
            Stage.DIARIZE, Stage.RECONCILE_SPEAKERS, Stage.BUILD_DIALOGUE);

    private final ChunkRepository chunks;
    private final MediaAssetRepository assets;
    private final DubProjectRepository projects;
    private final ProcessingProfileRepository profiles;
    private final OutputPublisher publisher;
    private final SpeechStagePlanner speech;

    public StagePlanner(ChunkRepository chunks, MediaAssetRepository assets, DubProjectRepository projects,
                        ProcessingProfileRepository profiles, OutputPublisher publisher, SpeechStagePlanner speech) {
        this.chunks = chunks;
        this.assets = assets;
        this.projects = projects;
        this.profiles = profiles;
        this.publisher = publisher;
        this.speech = speech;
    }

    // ------------------------------------------------------------------------------------------- planning

    public List<ProcessingChunk> plan(ProcessingJob job, Stage stage) {
        DubProject project = projects.findById(job.getProjectId())
                .orElseThrow(() -> new PlanningException("Project not found"));
        if (SPEECH_STAGES.contains(stage)) {
            return chunks.saveAll(speech.plan(job, stage, project, profile(project)));
        }
        MediaAsset original = assets.findFirstByProjectIdAndType(project.getId(), MediaAssetType.ORIGINAL_VIDEO)
                .orElseThrow(() -> new PlanningException("The project has no movie yet"));
        List<ProcessingChunk> planned = new ArrayList<>();
        switch (stage) {
            case INSPECT_MEDIA -> planned.add(new ProcessingChunk(job.getId(), stage, 0, null, null, "cpu",
                    Map.of("originalPath", original.getStoragePath())));
            case EXTRACT_AUDIO -> {
                long duration = requireDuration(project);
                int streamIndex = audioStreamIndex(job, project);
                long segment = profile(project).getExtractionSegmentMs();
                int index = 0;
                for (long start = 0; start < duration; start += segment) {
                    long end = Math.min(start + segment, duration);
                    planned.add(new ProcessingChunk(job.getId(), stage, index++, start, end, "cpu", Map.of(
                            "originalPath", original.getStoragePath(),
                            "audioStreamIndex", streamIndex,
                            "sampleRate", ASR_SAMPLE_RATE,
                            "expectedSamples", samples(end - start))));
                }
            }
            case MERGE_AUDIO -> {
                long duration = requireDuration(project);
                List<String> segmentPaths = new ArrayList<>();
                for (ProcessingChunk c : chunks.findByJobIdAndStageOrderByChunkIndexAsc(job.getId(),
                        Stage.EXTRACT_AUDIO)) {
                    MediaAsset a = assets.findById(c.getResultAssetId())
                            .orElseThrow(() -> new PlanningException("Segment " + c.getChunkIndex() + " has no audio"));
                    segmentPaths.add(a.getStoragePath());
                }
                planned.add(new ProcessingChunk(job.getId(), stage, 0, 0L, duration, "cpu", Map.of(
                        "segmentPaths", segmentPaths,
                        "sampleRate", ASR_SAMPLE_RATE,
                        "expectedSamples", samples(duration))));
            }
            default -> throw new PlanningException("Stage " + stage + " is not implemented yet");
        }
        return chunks.saveAll(planned);
    }

    /** Relative weight of each stage of a job in the overall progress (sums to 1). */
    public Map<Stage, Double> stageWeights(ProcessingJob job) {
        Map<Stage, Double> weights = new LinkedHashMap<>();
        JobType type = job.getType();
        if (type == JobType.EXTRACT_AUDIO) {
            weights.put(Stage.EXTRACT_AUDIO, 0.9);
            weights.put(Stage.MERGE_AUDIO, 0.1);
        } else if (type == JobType.TRANSCRIBE) {
            weights.putAll(SpeechStagePlanner.weights(job));
        } else {
            for (Stage s : type.stages()) {
                weights.put(s, 1.0 / type.stages().size());
            }
        }
        return weights;
    }

    public Map<String, Object> taskInput(ProcessingChunk chunk) {
        Map<String, Object> input = new HashMap<>(chunk.getParams());
        input.keySet().removeIf(k -> k.startsWith("_")); // engine-internal bookkeeping, not for the worker
        return input;
    }

    // ------------------------------------------------------------------------------------- accepting results

    /**
     * Verify and publish a finished attempt's result. Runs inside the engine's transaction, after fencing.
     * Publishing (the file move) is the last fallible step.
     */
    public void accept(ProcessingJob job, ProcessingChunk chunk, ProcessingAttempt attempt, CompleteRequest result) {
        if (SPEECH_STAGES.contains(chunk.getStage())) {
            DubProject project = projects.findById(job.getProjectId()).orElseThrow();
            speech.accept(job, chunk, attempt, result, project);
            return;
        }
        switch (chunk.getStage()) {
            case INSPECT_MEDIA -> acceptInspection(job, result);
            case EXTRACT_AUDIO -> {
                Output out = OutputPublisher.single(result, "audio");
                requireSamples(out, chunk);
                MediaAsset asset = publish(job, attempt, out, MediaAssetType.AUDIO_CHUNK,
                        "extracted/%s/segments/%04d-%s.wav".formatted(job.getId(), chunk.getChunkIndex(), attempt.getId()),
                        "audio/wav", Map.of("startMs", chunk.getStartMs(), "endMs", chunk.getEndMs(),
                                "sampleRate", ASR_SAMPLE_RATE, "channels", 1, "samples", out.info().get("samples")));
                chunk.setResultAssetId(asset.getId());
            }
            case MERGE_AUDIO -> {
                Output out = OutputPublisher.single(result, "audio");
                requireSamples(out, chunk);
                MediaAsset asset = publish(job, attempt, out, MediaAssetType.EXTRACTED_AUDIO,
                        "extracted/%s/audio-16k-mono-%s.wav".formatted(job.getId(), attempt.getId()),
                        "audio/wav", Map.of("durationMs", chunk.getEndMs(), "sampleRate", ASR_SAMPLE_RATE,
                                "channels", 1, "samples", out.info().get("samples"),
                                "audioStreamIndex", job.getParameters().getOrDefault("audioStreamIndex", 0)));
                chunk.setResultAssetId(asset.getId());
                job.setResultAssetId(asset.getId());
            }
            default -> throw new InvalidOutputException("Stage " + chunk.getStage() + " is not implemented");
        }
    }

    /** Side effects when a job completes. */
    public void onJobCompleted(ProcessingJob job) {
        if (job.getType() == JobType.EXTRACT_AUDIO) {
            DubProject project = projects.findById(job.getProjectId()).orElseThrow();
            if (!project.getStatus().atLeast(ProjectStatus.AUDIO_READY)) {
                project.setStatus(ProjectStatus.AUDIO_READY);
            }
        }
    }

    // ------------------------------------------------------------------------------------------ inspection

    @SuppressWarnings("unchecked")
    private void acceptInspection(ProcessingJob job, CompleteRequest result) {
        Map<String, Object> probe = result.metadata() == null ? Map.of() : result.metadata();
        DubProject project = projects.findById(job.getProjectId()).orElseThrow();
        MediaAsset original = assets.findFirstByProjectIdAndType(project.getId(), MediaAssetType.ORIGINAL_VIDEO)
                .orElseThrow();
        ProcessingProfile profile = profile(project);

        long durationMs = number(probe.get("durationMs"));
        String format = String.valueOf(probe.getOrDefault("formatName", ""));
        List<Map<String, Object>> audio = (List<Map<String, Object>>) probe.getOrDefault("audioStreams", List.of());
        List<Map<String, Object>> video = (List<Map<String, Object>>) probe.getOrDefault("videoStreams", List.of());
        String extension = MediaFiles.extension(original.getFilename()).orElse("");

        List<String> problems = new ArrayList<>();
        boolean formatOk = extension.equals("mkv") ? format.contains("matroska") : format.contains("mp4") || format.contains("mov");
        if (!formatOk) {
            problems.add("The file is not a valid %s movie (detected format: %s).".formatted(extension.toUpperCase(), format));
        }
        if (video.isEmpty()) {
            problems.add("The file has no video stream.");
        }
        if (audio.isEmpty()) {
            problems.add("The file has no audio stream to dub.");
        }
        if (durationMs <= 0) {
            problems.add("The movie's duration could not be read.");
        } else if (durationMs > profile.getMaxDurationMs()) {
            problems.add("The movie is %.1f minutes long; the limit of profile '%s' is %d minutes."
                    .formatted(durationMs / 60000.0, profile.getName(), profile.getMaxDurationMs() / 60000));
        }

        Map<String, Object> metadata = new HashMap<>(original.getMetadata());
        metadata.put("probe", probe);
        metadata.put("validation", Map.of("valid", problems.isEmpty(), "problems", problems));
        original.setMetadata(metadata);

        if (!problems.isEmpty()) {
            throw new JobCannotContinueException(String.join(" ", problems));
        }
        project.setDurationMs(durationMs);
        if (project.getSourceAudioStreamIndex() == null) {
            project.setSourceAudioStreamIndex(defaultAudioStream(audio));
        }
        if (project.getStatus() == ProjectStatus.CREATED) {
            project.setStatus(ProjectStatus.IMPORTED);
        }
    }

    private static int defaultAudioStream(List<Map<String, Object>> audio) {
        return audio.stream()
                .filter(s -> Boolean.TRUE.equals(s.get("default")))
                .findFirst().or(() -> audio.stream().findFirst())
                .map(s -> (int) number(s.get("index"))).orElse(0);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private MediaAsset publish(ProcessingJob job, ProcessingAttempt attempt, Output out, MediaAssetType type,
                               String targetInProject, String mime, Map<String, Object> metadata) {
        return publisher.publish(job, attempt, out, type,
                StorageService.projectDir(job.getProjectId()) + "/" + targetInProject, mime, metadata);
    }

    private static void requireSamples(Output out, ProcessingChunk chunk) {
        long expected = number(chunk.getParams().get("expectedSamples"));
        long actual = out.info() == null ? -1 : number(out.info().get("samples"));
        if (expected != actual) {
            throw new InvalidOutputException("Expected %d samples, worker produced %d".formatted(expected, actual));
        }
    }

    private long requireDuration(DubProject project) {
        if (project.getDurationMs() == null || project.getDurationMs() <= 0) {
            throw new PlanningException("The movie has not been inspected yet");
        }
        return project.getDurationMs();
    }

    private static int audioStreamIndex(ProcessingJob job, DubProject project) {
        Object fromJob = job.getParameters().get("audioStreamIndex");
        if (fromJob != null) {
            return (int) number(fromJob);
        }
        if (project.getSourceAudioStreamIndex() == null) {
            throw new PlanningException("No audio stream selected");
        }
        return project.getSourceAudioStreamIndex();
    }

    ProcessingProfile profile(DubProject project) {
        return (project.getProcessingProfileId() == null
                ? profiles.findFirstByDefaultProfileTrue()
                : profiles.findById(project.getProcessingProfileId()))
                .orElseThrow(() -> new PlanningException("No processing profile"));
    }

    static long samples(long durationMs) {
        return durationMs * ASR_SAMPLE_RATE / 1000;
    }

    static long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s.trim());
        }
        return -1;
    }

    static UUID uuid(Object value) {
        return value == null ? null : UUID.fromString(value.toString());
    }
}
