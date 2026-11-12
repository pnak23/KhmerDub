package com.khmerdub.api.project;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DubProjectRepository extends JpaRepository<DubProject, UUID> {
}
