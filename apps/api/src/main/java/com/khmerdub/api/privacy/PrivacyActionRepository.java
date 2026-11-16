package com.khmerdub.api.privacy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrivacyActionRepository extends JpaRepository<PrivacyAction, UUID> {

    List<PrivacyAction> findByProjectIdOrderByCreatedAtDesc(UUID projectId);
}
