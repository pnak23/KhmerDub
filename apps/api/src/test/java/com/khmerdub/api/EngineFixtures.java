package com.khmerdub.api;

import com.khmerdub.api.job.JobEngine;
import com.khmerdub.api.job.JobEnums.JobType;
import com.khmerdub.api.job.ProcessingJob;
import com.khmerdub.api.job.WorkerProtocol.ClaimResponse;
import com.khmerdub.api.job.WorkerProtocol.CompleteRequest;
import com.khmerdub.api.job.WorkerProtocol.Output;
import com.khmerdub.api.media.MediaAsset;
import com.khmerdub.api.media.MediaAssetRepository;
import com.khmerdub.api.media.MediaAssetType;
import com.khmerdub.api.project.ProjectService;
import com.khmerdub.api.project.ProjectViews.CreateProjectRequest;
import com.khmerdub.api.storage.StorageService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds projects and simulates what a worker would write and report. */
public class EngineFixtures {

    public static final String TRUNCATE = "TRUNCATE dub_projects, media_assets, processing_jobs, processing_chunks, "
            + "processing_attempts, job_events CASCADE";

    private final ProjectService projects;
    private final MediaAssetRepository assets;
    private final StorageService storage;
    private final JobEngine engine;
    private final JdbcTemplate jdbc;

    public EngineFixtures(ProjectService projects, MediaAssetRepository assets, StorageService storage,
                          JobEngine engine, JdbcTemplate jdbc) {
        this.projects = projects;
        this.assets = assets;
        this.storage = storage;
        this.engine = engine;
        this.jdbc = jdbc;
    }

    public void reset() {
        jdbc.execute(TRUNCATE);
    }

    /** A project whose original "movie" is a small placeholder file. */
    public UUID projectWithMovie(String fileName) throws IOException {
        UUID id = projects.create(new CreateProjectRequest("Test " + fileName, "auto")).summary().id();
        String relative = StorageService.projectDir(id) + "/original/" + fileName;
        Path file = storage.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, "placeholder movie".getBytes(StandardCharsets.UTF_8));
        assets.save(new MediaAsset(id, MediaAssetType.ORIGINAL_VIDEO, fileName, relative, "video/mp4",
                Files.size(file), StorageService.sha256(file), null));
        return id;
    }

    public static Map<String, Object> probe(long durationMs, boolean withAudio) {
        List<Map<String, Object>> audio = withAudio
                ? List.of(Map.of("index", 1, "codec", "aac", "channels", 2, "sampleRate", 48000, "default", true),
                          Map.of("index", 2, "codec", "aac", "channels", 2, "sampleRate", 48000, "default", false))
                : List.of();
        return Map.of("formatName", "mov,mp4,m4a,3gp,3g2,mj2", "durationMs", durationMs,
                "videoStreams", List.of(Map.of("index", 0, "codec", "h264", "width", 1280, "height", 720)),
                "audioStreams", audio);
    }

    /** Run the IMPORT (inspection) job through the engine with the given probe result. */
    public ProcessingJob inspect(UUID projectId, Map<String, Object> probe) {
        ProcessingJob job = engine.createJob(projectId, JobType.IMPORT, Map.of());
        engine.admitQueued();
        var order = engine.reserveDispatches("cpu", 1).getFirst();
        engine.claim(order.attemptId(), "test-worker");
        engine.complete(order.attemptId(), new CompleteRequest(List.of(), probe, Map.of("probeMs", 5)));
        return job;
    }

    /** Write a fake audio output into the attempt's scratch folder, like a worker would. */
    public Output audioOutput(ClaimResponse claim, String fileName, String content) throws IOException {
        String relative = claim.workDir() + "/" + fileName;
        Path file = storage.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        long samples = ((Number) claim.input().get("expectedSamples")).longValue();
        return new Output("audio", relative, Files.size(file), StorageService.sha256(file), Map.of("samples", samples));
    }

    public CompleteRequest completion(Output output) {
        return new CompleteRequest(List.of(output), Map.of(), Map.of("ffmpegMs", 1234));
    }

    /** Pretend time passed: make a running attempt's lease (or a dispatched attempt's claim deadline) overdue. */
    public void expireLease(UUID attemptId) {
        jdbc.update("UPDATE processing_attempts SET lease_expires_at = now() - interval '1 minute', "
                + "claim_deadline = now() - interval '1 minute' WHERE id = ?", attemptId);
    }

    public void clearBackoff() {
        jdbc.update("UPDATE processing_chunks SET not_before = NULL");
    }
}
