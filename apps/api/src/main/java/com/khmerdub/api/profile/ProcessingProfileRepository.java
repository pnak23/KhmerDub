package com.khmerdub.api.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessingProfileRepository extends JpaRepository<ProcessingProfile, UUID> {

    Optional<ProcessingProfile> findFirstByDefaultProfileTrue();

    List<ProcessingProfile> findAllByOrderByNameAsc();
}
