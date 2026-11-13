package com.khmerdub.api.job;

import com.khmerdub.api.job.JobEnums.AttemptStatus;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AttemptRepository extends JpaRepository<ProcessingAttempt, UUID> {

    @Query("select coalesce(max(a.attemptNo), 0) from ProcessingAttempt a where a.chunkId = :chunkId")
    int maxAttemptNo(UUID chunkId);

    List<ProcessingAttempt> findByStatusAndLeaseExpiresAtBefore(AttemptStatus status, OffsetDateTime time);

    List<ProcessingAttempt> findByStatusAndClaimDeadlineBefore(AttemptStatus status, OffsetDateTime time);

    List<ProcessingAttempt> findByChunkIdOrderByAttemptNoAsc(UUID chunkId);

    List<ProcessingAttempt> findByChunkIdIn(Collection<UUID> chunkIds);

    @Query("select a from ProcessingAttempt a where a.status not in :live and a.finishedAt < :before")
    List<ProcessingAttempt> findFinishedBefore(Collection<AttemptStatus> live, OffsetDateTime before);

    /** After an API restart, give live workers time to reconnect before their leases count as expired. */
    @Modifying
    @Query("""
            update ProcessingAttempt a set a.leaseExpiresAt = :until
            where a.status = com.khmerdub.api.job.JobEnums.AttemptStatus.RUNNING
              and (a.leaseExpiresAt is null or a.leaseExpiresAt < :until)""")
    int extendRunningLeases(OffsetDateTime until);

    @Modifying
    @Query("""
            update ProcessingAttempt a set a.claimDeadline = :until
            where a.status = com.khmerdub.api.job.JobEnums.AttemptStatus.DISPATCHED and a.claimDeadline < :until""")
    int extendClaimDeadlines(OffsetDateTime until);
}
