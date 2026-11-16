package com.khmerdub.api.dialogue;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DialogueRevisionRepository extends JpaRepository<DialogueRevision, UUID> {

    List<DialogueRevision> findByProjectIdOrderByRevisionNoDesc(UUID projectId);

    Optional<DialogueRevision> findFirstByProjectIdAndStatus(UUID projectId, DialogueRevision.Status status);

    Optional<DialogueRevision> findFirstBySourceJobId(UUID jobId);

    @Query("select coalesce(max(r.revisionNo), 0) from DialogueRevision r where r.projectId = ?1")
    int maxRevisionNo(UUID projectId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DialogueRevision r where r.id = ?1")
    Optional<DialogueRevision> lockById(UUID id);
}
