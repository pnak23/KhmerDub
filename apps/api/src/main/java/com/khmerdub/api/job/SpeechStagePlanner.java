package com.khmerdub.api.job;

import com.khmerdub.api.dialogue.DialogueImporter;
import com.khmerdub.api.dialogue.DialogueRevision;
import com.khmerdub.api.job.EngineExceptions.InvalidOutputException;
import com.khmerdub.api.job.EngineExceptions.PlanningException;
import com.khmerdub.api.job.JobEnums.ChunkStatus;
import com.khmerdub.api.job.JobEnums.Stage;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.Output;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.profile.ProcessingProfile;
import com.khmerdub.api.project.DubProject;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Planning and result acceptance for the speech pipeline (job type TRANSCRIBE):
 * SPEECH_MAP -> DETECT_LANGUAGE -> TRANSCRIBE (~5 min cores snapped to silence) -> DIARIZE (10 min chunks, 60 s
 * overlap) -> RECONCILE_SPEAKERS -> BUILD_DIALOGUE (imported as a new dialogue revision).
 *
 * <p>Privacy: speaker embeddings are published only to projects/{id}/private/fingerprints/ and voice samples only
 * to projects/{id}/private/voice-samples/ (a database CHECK enforces both). Their asset ids are kept in
 * engine-internal chunk params (prefix "_"), which are never sent to workers or exposed by the API.
 */
@Component
public class SpeechStagePlanner {

    static final long TRANSCRIBE_SEARCH_MS = 30_000;
    static final long MIN_CHUNK_MS = 60_000;
    private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,3}");
    private static final Pattern SPEAKER_CODE = Pattern.compile("SPK_\\d{2,3}");

    private final ChunkRepository chunks;
    private final MediaAssetRepository assets;
    private final StorageService storage;
    private final OutputPublisher publisher;
    private final DialogueImporter importer;
    private final ObjectMapper json;

    public SpeechStagePlanner(ChunkRepository chunks, MediaAssetRepository assets, StorageService storage,
                              OutputPublisher publisher, DialogueImporter importer, ObjectMapper json) {
        this.chunks = chunks;
        this.assets = assets;
        this.storage = storage;
        this.publisher = publisher;
        this.importer = importer;
        this.json = json;
    }

    public static boolean fast(ProcessingJob job) {
        return "FAST".equals(job.getParameters().get("mode"));
    }

    static Map<Stage, Double> weights(ProcessingJob job) {
        Map<Stage, Double> w = new EnumMap<>(Stage.class);
        boolean fast = fast(job);
        w.put(Stage.SPEECH_MAP, 0.02);
        w.put(Stage.DETECT_LANGUAGE, 0.01);
        w.put(Stage.TRANSCRIBE, fast ? 0.40 : 0.24);
        w.put(Stage.DIARIZE, fast ? 0.54 : 0.70);
        w.put(Stage.RECONCILE_SPEAKERS, 0.02);
        w.put(Stage.BUILD_DIALOGUE, 0.01);
        return w;
    }

    // =========================================================================================== planning

    List<ProcessingChunk> plan(ProcessingJob job, Stage stage, DubProject project, ProcessingProfile profile) {
        long duration = project.getDurationMs() == null ? 0 : project.getDurationMs();
        if (duration <= 0) {
            throw new PlanningException("The movie has not been inspected yet");
        }
        String audioPath = audio(job).getStoragePath();
        Map<String, Object> p = job.getParameters();
        boolean diarize = profile.isDiarizationEnabled() && !Boolean.FALSE.equals(p.get("diarize"));
        List<ProcessingChunk> planned = new ArrayList<>();
        switch (stage) {
            case SPEECH_MAP -> planned.add(chunk(job, stage, 0, 0, duration,
                    Map.of("audioPath", audioPath, "durationMs", duration)));
            case DETECT_LANGUAGE -> {
                ProcessingChunk c = chunk(job, stage, 0, 0, duration, Map.of("audioPath", audioPath,
                        "durationMs", duration, "speechMapPath", resultPath(job, Stage.SPEECH_MAP, 0),
                        "threads", profile.getWhisperCpuThreads(), "windows", 6));
                if (forcedLanguage(job) != null) {
                    c.setStatus(ChunkStatus.SKIPPED); // the user chose the language
                }
                planned.add(c);
            }
            case TRANSCRIBE -> {
                String language = language(job, project);
                long pad = profile.getTranscriptionOverlapMs() / 2;
                List<long[]> cores = transcriptionCores(speechRegions(job), duration, profile.getTranscriptionChunkMs());
                for (int i = 0; i < cores.size(); i++) {
                    long coreStart = cores.get(i)[0];
                    long coreEnd = cores.get(i)[1];
                    long start = Math.max(0, coreStart - pad);
                    long end = Math.min(duration, coreEnd + pad);
                    planned.add(chunk(job, stage, i, start, end, Map.of("audioPath", audioPath, "chunkIndex", i,
                            "startMs", start, "endMs", end, "coreStartMs", coreStart, "coreEndMs", coreEnd,
                            "language", language, "beamSize", profile.getWhisperBeamSize(),
                            "threads", profile.getWhisperCpuThreads())));
                }
            }
            case DIARIZE -> {
                if (!diarize) {
                    planned.add(skipped(job, stage, duration));
                    break;
                }
                double step = fast(job) ? profile.getDiarizationStepFast() : profile.getDiarizationStepQuality();
                List<long[]> windows = diarizationChunks(duration, profile.getDiarizationChunkMs(),
                        profile.getDiarizationOverlapMs());
                for (int i = 0; i < windows.size(); i++) {
                    long start = windows.get(i)[0];
                    long end = windows.get(i)[1];
                    planned.add(chunk(job, stage, i, start, end, Map.of("audioPath", audioPath, "chunkIndex", i,
                            "startMs", start, "endMs", end, "segmentationStep", step,
                            "threads", profile.getDiarizationThreads())));
                }
            }
            case RECONCILE_SPEAKERS -> {
                List<ProcessingChunk> diarized = chunks.findByJobIdAndStageOrderByChunkIndexAsc(job.getId(), Stage.DIARIZE);
                if (diarized.stream().allMatch(c -> c.getStatus() == ChunkStatus.SKIPPED)) {
                    planned.add(skipped(job, stage, duration));
                    break;
                }
                List<Map<String, Object>> inputs = new ArrayList<>();
                for (ProcessingChunk c : diarized) {
                    MediaAsset embeddings = assets.findById(StagePlanner.uuid(c.getParams().get("_embeddingsAssetId")))
                            .orElseThrow(() -> new PlanningException("Speaker data of chunk " + c.getChunkIndex()
                                    + " is missing (were voice fingerprints forgotten during the run?)"));
                    inputs.add(Map.of("index", c.getChunkIndex(), "diarizationPath", resultPath(c),
                            "embeddingsPath", embeddings.getStoragePath()));
                }
                planned.add(chunk(job, stage, 0, 0, duration, Map.of("audioPath", audioPath, "chunks", inputs,
                        "threshold", profile.getSpeakerMatchThreshold(), "samplesPerSpeaker", 3)));
            }
            case BUILD_DIALOGUE -> {
                List<String> transcripts = new ArrayList<>();
                for (ProcessingChunk c : chunks.findByJobIdAndStageOrderByChunkIndexAsc(job.getId(), Stage.TRANSCRIBE)) {
                    transcripts.add(resultPath(c));
                }
                ProcessingChunk reconcile = chunks.findByJobIdAndStageOrderByChunkIndexAsc(job.getId(),
                        Stage.RECONCILE_SPEAKERS).getFirst();
                Map<String, Object> params = new HashMap<>(Map.of("transcriptPaths", transcripts,
                        "speechMapPath", resultPath(job, Stage.SPEECH_MAP, 0), "language", language(job, project)));
                if (reconcile.getStatus() != ChunkStatus.SKIPPED) {
                    params.put("speakersPath", resultPath(reconcile));
                }
                planned.add(chunk(job, stage, 0, 0, duration, params));
            }
            default -> throw new PlanningException("Not a speech stage: " + stage);
        }
        return planned;
    }

    /** Contiguous cores of ~targetMs; each boundary moved to the middle of the longest silence within +/-30 s. */
    static List<long[]> transcriptionCores(List<long[]> regions, long duration, long targetMs) {
        List<long[]> cores = new ArrayList<>();
        long start = 0;
        while (start < duration) {
            long end;
            if (duration - start <= targetMs + targetMs / 3) {
                end = duration; // avoid a tiny last chunk
            } else {
                end = snap(regions, start + targetMs, TRANSCRIBE_SEARCH_MS);
                if (end - start < MIN_CHUNK_MS) {
                    end = start + targetMs;
                }
            }
            cores.add(new long[] {start, end});
            start = end;
        }
        return cores;
    }

    static long snap(List<long[]> regions, long target, long search) {
        long lo = target - search;
        long hi = target + search;
        long best = target;
        long bestLen = -1;
        Long previousEnd = null;
        for (long[] r : regions) {
            if (previousEnd != null) {
                long gs = Math.max(previousEnd, lo);
                long ge = Math.min(r[0], hi);
                if (ge > gs && ge - gs > bestLen) {
                    best = (gs + ge) / 2;
                    bestLen = ge - gs;
                }
            }
            previousEnd = r[1];
        }
        return best;
    }

    /** Fixed windows: [0, size], [size - overlap, 2 size - overlap], ... (the M3b-verified 10 min / 60 s layout). */
    static List<long[]> diarizationChunks(long duration, long size, long overlap) {
        List<long[]> windows = new ArrayList<>();
        long start = 0;
        while (true) {
            long end = Math.min(duration, start + size);
            windows.add(new long[] {start, end});
            if (end >= duration) {
                return windows;
            }
            start = end - overlap;
        }
    }

    // ========================================================================================== accepting

    @SuppressWarnings("unchecked")
    void accept(ProcessingJob job, ProcessingChunk chunk, ProcessingAttempt attempt, CompleteRequest result,
                DubProject project) {
        String jobDir = "transcription/" + job.getId();
        switch (chunk.getStage()) {
            case SPEECH_MAP -> {
                MediaAsset a = publish(job, attempt, OutputPublisher.single(result, "speechMap"), MediaAssetType.SPEECH_MAP,
                        jobDir + "/speech-map-" + attempt.getId() + ".json", Map.of());
                chunk.setResultAssetId(a.getId());
            }
            case DETECT_LANGUAGE -> {
                Map<String, Object> md = result.metadata() == null ? Map.of() : result.metadata();
                String language = String.valueOf(md.get("language"));
                if (!LANGUAGE.matcher(language).matches()) {
                    throw new InvalidOutputException("Unrecognised language code from detection: " + language);
                }
                project.setDetectedLanguage(language);
                Map<String, Object> params = new HashMap<>(chunk.getParams());
                params.put("_detected", Map.of("language", language, "agreement", md.getOrDefault("agreement", 0),
                        "meanProbability", md.getOrDefault("meanProbability", 0),
                        "windows", md.getOrDefault("windows", List.of())));
                chunk.setParams(params);
            }
            case TRANSCRIBE -> {
                MediaAsset a = publish(job, attempt, OutputPublisher.single(result, "transcript"), MediaAssetType.TRANSCRIPT,
                        "%s/transcript-%04d-%s.json".formatted(jobDir, chunk.getChunkIndex(), attempt.getId()),
                        Map.of("chunkIndex", chunk.getChunkIndex()));
                chunk.setResultAssetId(a.getId());
            }
            case DIARIZE -> {
                MediaAsset turns = publish(job, attempt, OutputPublisher.single(result, "diarization"),
                        MediaAssetType.DIARIZATION,
                        "%s/diarization-%04d-%s.json".formatted(jobDir, chunk.getChunkIndex(), attempt.getId()),
                        Map.of("chunkIndex", chunk.getChunkIndex()));
                MediaAsset embeddings = publishPrivate(job, attempt, OutputPublisher.single(result, "embeddings"),
                        MediaAssetType.SPEAKER_EMBEDDING, "fingerprints",
                        "%s-diarize-%04d-%s.npy".formatted(job.getId(), chunk.getChunkIndex(), attempt.getId()),
                        Map.of("jobId", job.getId().toString(), "chunkIndex", chunk.getChunkIndex()));
                chunk.setResultAssetId(turns.getId());
                Map<String, Object> params = new HashMap<>(chunk.getParams());
                params.put("_embeddingsAssetId", embeddings.getId().toString());
                chunk.setParams(params);
                project.setVoiceFingerprintsForgottenAt(null);
            }
            case RECONCILE_SPEAKERS -> {
                MediaAsset speakers = publish(job, attempt, OutputPublisher.single(result, "speakers"),
                        MediaAssetType.DIARIZATION, "%s/speakers-%s.json".formatted(jobDir, attempt.getId()),
                        Map.of("kind", "speakers"));
                publishPrivate(job, attempt, OutputPublisher.single(result, "embeddings"),
                        MediaAssetType.SPEAKER_EMBEDDING, "fingerprints",
                        "%s-speakers-%s.npy".formatted(job.getId(), attempt.getId()),
                        Map.of("jobId", job.getId().toString(), "kind", "speakers"));
                int n = 0;
                for (Output sample : OutputPublisher.all(result, "sample")) {
                    Map<String, Object> info = sample.info() == null ? Map.of() : sample.info();
                    String code = String.valueOf(info.get("speaker"));
                    if (!SPEAKER_CODE.matcher(code).matches()) {
                        throw new InvalidOutputException("Voice sample with invalid speaker code");
                    }
                    publishPrivate(job, attempt, sample, MediaAssetType.SPEAKER_SAMPLE, "voice-samples",
                            "%s-%s-%d-%s.wav".formatted(job.getId(), code, ++n, attempt.getId()),
                            Map.of("jobId", job.getId().toString(), "speaker", code,
                                    "startMs", info.getOrDefault("startMs", 0), "endMs", info.getOrDefault("endMs", 0)));
                }
                chunk.setResultAssetId(speakers.getId());
                project.setVoiceSamplesDeletedAt(null);
            }
            case BUILD_DIALOGUE -> {
                MediaAsset a = publish(job, attempt, OutputPublisher.single(result, "dialogue"), MediaAssetType.DIALOGUE,
                        "dialogue/%s/dialogue-%s.json".formatted(job.getId(), attempt.getId()), Map.of());
                Map<String, Object> dialogue = importer.read(storage.resolve(a.getStoragePath()));
                DialogueRevision revision = importer.importRevision(job, dialogue, project.getDurationMs());
                chunk.setResultAssetId(a.getId());
                job.setResultAssetId(a.getId());
                Map<String, Object> params = new HashMap<>(chunk.getParams());
                params.put("_revisionId", revision.getId().toString());
                chunk.setParams(params);
            }
            default -> throw new InvalidOutputException("Not a speech stage: " + chunk.getStage());
        }
    }

    // ============================================================================================ helpers

    private MediaAsset publish(ProcessingJob job, ProcessingAttempt attempt, Output out, MediaAssetType type,
                               String targetInProject, Map<String, Object> metadata) {
        return publisher.publish(job, attempt, out, type,
                StorageService.projectDir(job.getProjectId()) + "/" + targetInProject, "application/json", metadata);
    }

    private MediaAsset publishPrivate(ProcessingJob job, ProcessingAttempt attempt, Output out, MediaAssetType type,
                                      String kind, String fileName, Map<String, Object> metadata) {
        String mime = type == MediaAssetType.SPEAKER_SAMPLE ? "audio/wav" : "application/octet-stream";
        return publisher.publish(job, attempt, out, type, StorageService.privateDir(job.getProjectId(), kind) + "/" + fileName,
                mime, metadata);
    }

    private MediaAsset audio(ProcessingJob job) {
        Object id = job.getParameters().get("audioAssetId");
        return assets.findById(StagePlanner.uuid(id))
                .filter(a -> a.getType() == MediaAssetType.EXTRACTED_AUDIO && a.getProjectId().equals(job.getProjectId()))
                .orElseThrow(() -> new PlanningException("The extracted audio of this project is missing"));
    }

    private ProcessingChunk chunk(ProcessingJob job, Stage stage, int index, long start, long end,
                                  Map<String, Object> params) {
        return new ProcessingChunk(job.getId(), stage, index, start, end, "cpu", params);
    }

    private ProcessingChunk skipped(ProcessingJob job, Stage stage, long duration) {
        ProcessingChunk c = chunk(job, stage, 0, 0, duration, Map.of("skipped", "speaker identification is off"));
        c.setStatus(ChunkStatus.SKIPPED);
        return c;
    }

    private String resultPath(ProcessingJob job, Stage stage, int index) {
        ProcessingChunk c = chunks.findByJobIdAndStageOrderByChunkIndexAsc(job.getId(), stage).get(index);
        return resultPath(c);
    }

    private String resultPath(ProcessingChunk c) {
        return assets.findById(c.getResultAssetId())
                .orElseThrow(() -> new PlanningException("Result of %s #%d is missing".formatted(c.getStage(), c.getChunkIndex())))
                .getStoragePath();
    }

    private static String forcedLanguage(ProcessingJob job) {
        Object l = job.getParameters().get("language");
        return l == null || "auto".equals(l) ? null : l.toString();
    }

    private String language(ProcessingJob job, DubProject project) {
        String forced = forcedLanguage(job);
        if (forced != null) {
            return forced;
        }
        if (project.getDetectedLanguage() == null) {
            throw new PlanningException("The spoken language could not be detected; choose it and try again");
        }
        return project.getDetectedLanguage();
    }

    @SuppressWarnings("unchecked")
    private List<long[]> speechRegions(ProcessingJob job) {
        String path = resultPath(job, Stage.SPEECH_MAP, 0);
        try (InputStream in = Files.newInputStream(storage.resolve(path))) {
            Map<String, Object> map = json.readValue(in, new TypeReference<Map<String, Object>>() { });
            List<long[]> regions = new ArrayList<>();
            for (Object o : (List<Object>) map.get("regions")) {
                List<Number> r = (List<Number>) o;
                regions.add(new long[] {r.get(0).longValue(), r.get(1).longValue()});
            }
            return regions;
        } catch (IOException | RuntimeException e) { // JacksonException is a RuntimeException in Jackson 3
            throw new PlanningException("The speech map could not be read: " + e.getClass().getSimpleName());
        }
    }

    static UUID id(Object v) {
        return v == null ? null : UUID.fromString(v.toString());
    }
}
