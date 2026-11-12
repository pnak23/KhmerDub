package com.khmerdub.api.health;

import com.khmerdub.api.config.KhmerDubProperties;
import com.khmerdub.api.health.SystemHealth.ComponentHealth;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Aggregates the health of everything KhmerDub depends on into one status for the UI. */
@Service
public class SystemHealthService {

    private static final long GB = 1024L * 1024 * 1024;
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final RestClient aiService;
    private final KhmerDubProperties props;

    public SystemHealthService(JdbcTemplate jdbc, RestClient aiServiceRestClient, KhmerDubProperties props) {
        this.jdbc = jdbc;
        this.aiService = aiServiceRestClient;
        this.props = props;
    }

    public SystemHealth check() {
        Map<String, ComponentHealth> components = new LinkedHashMap<>();
        components.put("database", database());
        components.put("storage", storage());
        components.put("aiService", aiService());
        return new SystemHealth(overall(components), props.version(), Instant.now(), components);
    }

    static HealthStatus overall(Map<String, ComponentHealth> components) {
        if (components.get("database").status() == HealthStatus.DOWN) {
            return HealthStatus.DOWN;
        }
        boolean allUp = components.values().stream().allMatch(c -> c.status() == HealthStatus.UP);
        return allUp ? HealthStatus.UP : HealthStatus.WARN;
    }

    ComponentHealth database() {
        try {
            String serverVersion = jdbc.queryForObject("SHOW server_version", String.class);
            String schemaVersion = jdbc.queryForObject(
                    "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class);
            return new ComponentHealth(HealthStatus.UP,
                    Map.of("postgresVersion", serverVersion, "schemaVersion", schemaVersion));
        } catch (DataAccessException e) {
            return new ComponentHealth(HealthStatus.DOWN,
                    Map.of("error", String.valueOf(e.getMostSpecificCause().getMessage())));
        }
    }

    ComponentHealth storage() {
        Path root = props.storageRoot();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("root", root.toString());
        if (!Files.isDirectory(root)) {
            details.put("error", "Storage root does not exist");
            return new ComponentHealth(HealthStatus.DOWN, details);
        }
        try {
            FileStore store = Files.getFileStore(root);
            long free = store.getUsableSpace();
            long minFree = props.minFreeDiskGb() * GB;
            details.put("freeBytes", free);
            details.put("totalBytes", store.getTotalSpace());
            details.put("minFreeBytes", minFree);

            Path tempDir = Files.createDirectories(root.resolve("temp"));
            Path probe = Files.createTempFile(tempDir, ".health-", ".tmp");
            Files.delete(probe);
            details.put("writable", true);

            if (free < minFree) {
                details.put("warning", "Free disk space is below %d GB".formatted(props.minFreeDiskGb()));
                return new ComponentHealth(HealthStatus.WARN, details);
            }
            return new ComponentHealth(HealthStatus.UP, details);
        } catch (IOException e) {
            details.put("writable", false);
            details.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            return new ComponentHealth(HealthStatus.DOWN, details);
        }
    }

    ComponentHealth aiService() {
        try {
            Map<String, Object> body = aiService.get().uri("/health").retrieve().body(JSON_MAP);
            if (body == null) {
                return new ComponentHealth(HealthStatus.DOWN, Map.of("error", "Empty response"));
            }
            return new ComponentHealth(HealthStatus.parse(body.get("status")), body);
        } catch (RestClientException e) {
            return new ComponentHealth(HealthStatus.DOWN, Map.of(
                    "url", props.aiService().url().toString(),
                    "error", "Unreachable (" + e.getClass().getSimpleName() + ")"));
        }
    }
}
