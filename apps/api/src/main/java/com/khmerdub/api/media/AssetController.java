package com.khmerdub.api.media;

import com.khmerdub.api.error.ApiException;
import com.khmerdub.api.profile.ProcessingProfile;
import com.khmerdub.api.profile.ProcessingProfileRepository;
import com.khmerdub.api.project.ProjectViews.AssetView;
import com.khmerdub.api.storage.StorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AssetController {

    private final MediaAssetRepository assets;
    private final ProcessingProfileRepository profiles;
    private final StorageService storage;

    public AssetController(MediaAssetRepository assets, ProcessingProfileRepository profiles, StorageService storage) {
        this.assets = assets;
        this.profiles = profiles;
        this.storage = storage;
    }

    @GetMapping("/assets/{id}")
    public AssetView get(@PathVariable UUID id) {
        return AssetView.of(assets.findById(id).orElseThrow(() -> ApiException.notFound("Asset")));
    }

    /**
     * Read-only file content with HTTP Range support (for audio/video preview). Files are opened through NIO,
     * which on Windows allows other processes to rename or delete them while they are being read.
     */
    @GetMapping("/assets/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable UUID id) {
        MediaAsset asset = assets.findById(id).orElseThrow(() -> ApiException.notFound("Asset"));
        Path file = storage.resolve(asset.getStoragePath());
        if (!Files.isRegularFile(file)) {
            throw ApiException.notFound("File of asset");
        }
        MediaType type = asset.getMimeType() == null ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(asset.getMimeType());
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }

    public record ProfileView(UUID id, String name, String description, boolean isDefault, long maxDurationMs,
                              long extractionSegmentMs, long transcriptionChunkMs) {
    }

    @GetMapping("/processing-profiles")
    public List<ProfileView> profiles() {
        return profiles.findAllByOrderByNameAsc().stream()
                .map((ProcessingProfile p) -> new ProfileView(p.getId(), p.getName(), p.getDescription(),
                        p.isDefaultProfile(), p.getMaxDurationMs(), p.getExtractionSegmentMs(),
                        p.getTranscriptionChunkMs()))
                .toList();
    }
}
