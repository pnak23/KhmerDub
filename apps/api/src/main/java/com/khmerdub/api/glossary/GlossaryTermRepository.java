package com.khmerdub.api.glossary;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, UUID> {

    List<GlossaryTerm> findByProjectIdOrderByCategoryAscSourceTermAsc(UUID projectId);
}
