package com.khmerdub.api.job;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobEventRepository extends JpaRepository<JobEvent, UUID> {

    List<JobEvent> findByJobIdOrderBySeqDesc(UUID jobId, Limit limit);
}
