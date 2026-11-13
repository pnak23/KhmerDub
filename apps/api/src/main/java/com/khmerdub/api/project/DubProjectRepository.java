package com.khmerdub.api.project;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DubProjectRepository extends JpaRepository<DubProject, UUID> {

    List<DubProject> findAllByOrderByUpdatedAtDesc();

    List<DubProject> findByArchivedAtIsNullOrderByUpdatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DubProject p where p.id = :id")
    Optional<DubProject> lockById(UUID id);
}
