package com.khmerdub.api.dialogue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpeakerRepository extends JpaRepository<Speaker, UUID> {

    List<Speaker> findByRevisionIdOrderByDisplayOrderAsc(UUID revisionId);

    long countByRevisionId(UUID revisionId);
}
