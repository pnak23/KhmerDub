package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEnums.ChunkStatus;
import com.khmerdub.api.job.JobEnums.Stage;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChunkRepository extends JpaRepository<ProcessingChunk, UUID> {

    List<ProcessingChunk> findByJobId(UUID jobId);

    List<ProcessingChunk> findByJobIdAndStageOrderByChunkIndexAsc(UUID jobId, Stage stage);

    @Query("select count(c) from ProcessingChunk c where c.queue = :queue and c.status in :statuses")
    long countByQueueAndStatusIn(String queue, Collection<ChunkStatus> statuses);

    /** Dispatch candidates: pending chunks of running jobs, in the job's current stage, past their back-off. */
    @Query("""
            select c from ProcessingChunk c, ProcessingJob j
            where c.jobId = j.id and j.status = com.khmerdub.api.job.JobEnums.JobStatus.RUNNING
              and c.stage = j.currentStage and c.status = com.khmerdub.api.job.JobEnums.ChunkStatus.PENDING
              and c.queue = :queue and (c.notBefore is null or c.notBefore <= :now)
            order by case when j.type = com.khmerdub.api.job.JobEnums.JobType.IMPORT then 0 else 1 end,
                     j.createdAt, c.chunkIndex""")
    List<ProcessingChunk> findDispatchCandidates(String queue, OffsetDateTime now, Limit limit);
}
