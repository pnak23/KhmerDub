package com.khmerdub.api.media;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    Optional<MediaAsset> findFirstByProjectIdAndType(UUID projectId, MediaAssetType type);

    List<MediaAsset> findByProjectIdOrderByCreatedAtAsc(UUID projectId);
}
