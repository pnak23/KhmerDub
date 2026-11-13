package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEnums.JobStatus;
import com.khmerdub.api.job.JobEnums.JobType;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Lock order across the engine is always: job, then chunk, then attempt. */
public interface JobRepository extends JpaRepository<ProcessingJob, UUID> {

    /** Every engine transition starts by locking the job row; this serialises all changes within a job. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ProcessingJob j where j.id = :id")
    Optional<ProcessingJob> lockById(UUID id);

    List<ProcessingJob> findByStatusInOrderByCreatedAtAsc(Collection<JobStatus> statuses);

    List<ProcessingJob> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    boolean existsByProjectIdAndTypeAndStatusIn(UUID projectId, JobType type, Collection<JobStatus> statuses);
}
