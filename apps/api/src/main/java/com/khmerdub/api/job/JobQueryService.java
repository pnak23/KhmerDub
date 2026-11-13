package com.khmerdub.api.job;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.job.JobViews.AttemptView;
import com.khmerdub.api.job.JobViews.ChunkView;
import com.khmerdub.api.job.JobViews.EventView;
import com.khmerdub.api.job.JobViews.JobView;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class JobQueryService {

    private final JobRepository jobs;
    private final ChunkRepository chunks;
    private final AttemptRepository attempts;
    private final JobEventRepository events;

    public JobQueryService(JobRepository jobs, ChunkRepository chunks, AttemptRepository attempts,
                           JobEventRepository events) {
        this.jobs = jobs;
        this.chunks = chunks;
        this.attempts = attempts;
        this.events = events;
    }

    public JobView view(UUID jobId) {
        ProcessingJob job = jobs.findById(jobId).orElseThrow(() -> ApiException.notFound("Job"));
        List<ProcessingChunk> jobChunks = chunks.findByJobId(jobId);
        Map<UUID, List<AttemptView>> attemptsByChunk = attempts
                .findByChunkIdIn(jobChunks.stream().map(ProcessingChunk::getId).toList()).stream()
                .sorted(Comparator.comparingInt(ProcessingAttempt::getAttemptNo))
                .collect(Collectors.groupingBy(ProcessingAttempt::getChunkId,
                        Collectors.mapping(AttemptView::of, Collectors.toList())));
        List<JobEnums.Stage> order = job.getType().stages();
        List<ChunkView> chunkViews = jobChunks.stream()
                .sorted(Comparator.comparingInt((ProcessingChunk c) -> order.indexOf(c.getStage()))
                        .thenComparingInt(ProcessingChunk::getChunkIndex))
                .map(c -> new ChunkView(c.getId(), c.getStage(), c.getChunkIndex(), c.getStartMs(), c.getEndMs(),
                        c.getStatus(), c.getStatus().done() ? 100 : c.getProgress(), c.getRetryCount(),
                        c.getCurrentAttemptId(), c.getResultAssetId(), c.getErrorDetails(),
                        attemptsByChunk.getOrDefault(c.getId(), List.of())))
                .toList();
        return new JobView(job.getId(), job.getProjectId(), job.getType(), job.getStatus(), job.getCurrentStage(),
                order, job.getProgress(), job.getMaxAttempts(), job.getRetryCount(), job.getErrorMessage(),
                job.getResultAssetId(), job.getParameters(), job.getCreatedAt(), job.getStartedAt(),
                job.getFinishedAt(), job.getUpdatedAt(), job.getVersion(), chunkViews);
    }

    public List<EventView> events(UUID jobId, int limit) {
        if (!jobs.existsById(jobId)) {
            throw ApiException.notFound("Job");
        }
        return events.findByJobIdOrderBySeqDesc(jobId, Limit.of(Math.clamp(limit, 1, 500))).stream()
                .map(EventView::of).toList();
    }
}
