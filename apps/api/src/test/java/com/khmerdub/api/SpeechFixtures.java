package com.khmerdub.api;

import com.khmerdub.api.dialogue.DialogueViews.StartTranscriptionRequest;
import com.khmerdub.api.dialogue.TranscriptionService;
import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEngine.DispatchOrder;
import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.Stage;
import com.khmerdub.api.job.JobRepository;
import com.khmerdub.api.job.WorkerProtocol.ClaimResponse;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.FailRequest;
import com.khmerdub.api.job.WorkerProtocol.Output;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.project.ProjectViews.StartExtractionRequest;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Simulates the Linux speech worker: writes the files a real worker would write and reports them. */
public class SpeechFixtures {

    static final long MIN = 60_000;

    private final EngineFixtures fx;
    private final JobEngine engine;
    private final JobRepository jobs;
    private final ProjectService projects;
    private final TranscriptionService transcription;
    private final StorageService storage;
    private final ObjectMapper json;
    /** Worker inputs seen per stage (last claim of each stage). */
    public final Map<Stage, List<ClaimResponse>> claims = new EnumMap<>(Stage.class);

    public SpeechFixtures(EngineFixtures fx, JobEngine engine, JobRepository jobs, ProjectService projects,
                          TranscriptionService transcription, StorageService storage, ObjectMapper json) {
        this.fx = fx;
        this.engine = engine;
        this.jobs = jobs;
        this.projects = projects;
        this.transcription = transcription;
        this.storage = storage;
        this.json = json;
    }

    /** A project whose audio has been extracted (fake 16 kHz WAV), status AUDIO_READY. */
    public UUID audioReadyProject(long durationMs) throws IOException {
        UUID p = fx.projectWithMovie("movie.mp4");
        fx.inspect(p, EngineFixtures.probe(durationMs, true));
        UUID job = projects.startExtraction(p, new StartExtractionRequest(1, "auto")).id();
        engine.admitQueued();
        while (jobs.findById(job).orElseThrow().getStatus() == JobStatus.RUNNING) {
            DispatchOrder o = engine.reserveDispatches("cpu", 1).getFirst();
            ClaimResponse c = engine.claim(o.attemptId(), "w1");
            engine.complete(o.attemptId(), fx.completion(fx.audioOutput(c, "a.wav", "pcm")));
        }
        return p;
    }

    public UUID start(UUID project, StartTranscriptionRequest request) {
        return transcription.start(project, request).id();
    }

    /** Run the TRANSCRIBE job to its end. failAt: stage whose first attempt reports a non-retryable failure. */
    public JobStatus run(UUID jobId, Stage failAt, String dialogueVariant) throws IOException {
        engine.admitQueued();
        for (int guard = 0; guard < 200; guard++) {
            JobStatus status = jobs.findById(jobId).orElseThrow().getStatus();
            if (status.terminal()) {
                return status;
            }
            List<DispatchOrder> orders = engine.reserveDispatches("cpu", 1);
            if (orders.isEmpty()) {
                throw new IllegalStateException("Job is " + status + " but nothing was dispatched");
            }
            DispatchOrder o = orders.getFirst();
            ClaimResponse c = engine.claim(o.attemptId(), "speech-worker");
            claims.computeIfAbsent(c.stage(), s -> new ArrayList<>()).add(c);
            if (c.stage() == failAt) {
                engine.fail(o.attemptId(), new FailRequest(false, false, "injected failure", Map.of()));
                continue;
            }
            engine.heartbeat(o.attemptId(), 50);
            engine.complete(o.attemptId(), complete(c, dialogueVariant));
        }
        throw new IllegalStateException("Job did not finish");
    }

    private CompleteRequest complete(ClaimResponse c, String variant) throws IOException {
        long duration = ((Number) c.input().getOrDefault("durationMs", 25 * MIN)).longValue();
        return switch (c.stage()) {
            case SPEECH_MAP -> done(file(c, "speech-map.json", "speechMap", Map.of("format", "khmerdub.speech-map.v1",
                    "regions", List.of(List.of(0, 295_000), List.of(305_000, 900_000), List.of(905_000, 25 * MIN)))));
            case DETECT_LANGUAGE -> new CompleteRequest(List.of(), Map.of("language", "zh", "agreement", 1.0,
                    "meanProbability", 0.99, "windows", List.of()), Map.of());
            case TRANSCRIBE -> done(file(c, "transcript.json", "transcript", Map.of("format", "khmerdub.transcript-chunk.v1")));
            case DIARIZE -> new CompleteRequest(List.of(
                    file(c, "diarization.json", "diarization", Map.of("format", "khmerdub.diarization-chunk.v1")),
                    raw(c, "private/fingerprints/diarize.npy", "embeddings", new byte[] {1, 2, 3, 4}, Map.of())),
                    Map.of(), Map.of());
            case RECONCILE_SPEAKERS -> new CompleteRequest(List.of(
                    file(c, "speakers.json", "speakers", Map.of("format", "khmerdub.speakers.v1")),
                    raw(c, "private/fingerprints/speakers.npy", "embeddings", new byte[] {5, 6, 7}, Map.of()),
                    raw(c, "private/voice-samples/SPK_01-1.wav", "sample", new byte[] {9, 9},
                            Map.of("speaker", "SPK_01", "startMs", 1000, "endMs", 4000)),
                    raw(c, "private/voice-samples/SPK_02-1.wav", "sample", new byte[] {8, 8},
                            Map.of("speaker", "SPK_02", "startMs", 6000, "endMs", 9000))),
                    Map.of(), Map.of());
            case BUILD_DIALOGUE -> done(file(c, "dialogue.json", "dialogue", dialogue(variant, duration)));
            default -> throw new IllegalStateException("unexpected stage " + c.stage());
        };
    }

    public static Map<String, Object> dialogue(String variant, long durationMs) {
        return Map.of("format", "khmerdub.dialogue.v1", "language", "zh",
                "speakers", List.of(Map.of("code", "SPK_01", "speechMs", 3000, "lines", 2, "displayOrder", 0),
                        Map.of("code", "SPK_02", "speechMs", 3000, "lines", 1, "displayOrder", 1)),
                "lines", List.of(
                        line(0, 1000, 2500, "SPK_01", "你好" + variant),
                        line(1, 6000, 8000, "SPK_02", "今天去哪里" + variant),
                        line(2, 10_000, 12_000, "SPK_01", "回家吧" + variant)),
                "summary", Map.of("duplicateWords", 0, "uncoveredSpeechRegions", 0));
    }

    private static Map<String, Object> line(int seq, int s, int e, String spk, String text) {
        return Map.of("seq", seq, "startMs", s, "endMs", e, "speaker", spk, "purity", 1.0, "text", text,
                "avgLogprob", -0.2, "words", List.of(Map.of("w", text, "s", s, "e", e, "p", 0.9)), "flags", Map.of());
    }

    private static CompleteRequest done(Output o) {
        return new CompleteRequest(List.of(o), Map.of(), Map.of("wallMs", 10));
    }

    private Output file(ClaimResponse c, String name, String role, Map<String, Object> content) throws IOException {
        return raw(c, name, role, json.writeValueAsString(content).getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private Output raw(ClaimResponse c, String name, String role, byte[] content, Map<String, Object> info)
            throws IOException {
        String relative = c.workDir() + "/" + name;
        Path f = storage.resolve(relative);
        Files.createDirectories(f.getParent());
        Files.write(f, content);
        return new Output(role, relative, Files.size(f), StorageService.sha256(f), info);
    }
}
