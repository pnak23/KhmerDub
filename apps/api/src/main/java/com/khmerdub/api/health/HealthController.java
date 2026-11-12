package com.khmerdub.api.health;

import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api")
public class HealthController {

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_MAP = new ParameterizedTypeReference<>() {
    };

    private final SystemHealthService healthService;
    private final RestClient aiService;

    public HealthController(SystemHealthService healthService, RestClient aiServiceRestClient) {
        this.healthService = healthService;
        this.aiService = aiServiceRestClient;
    }

    /** Aggregated system status for the UI. Always HTTP 200; read {@code status} in the body. */
    @GetMapping("/health")
    public SystemHealth health() {
        return healthService.check();
    }

    /**
     * Sends a real task through Spring -> FastAPI -> Redis -> Celery worker and back.
     * This proves the whole processing path works, not just that each service is up.
     */
    @PostMapping("/system/worker-roundtrip")
    public Map<String, Object> workerRoundTrip() {
        try {
            return aiService.post().uri("/internal/diagnostics/roundtrip").retrieve().body(JSON_MAP);
        } catch (RestClientException e) {
            return Map.of("ok", false, "error", "AI service unreachable (" + e.getClass().getSimpleName() + ")");
        }
    }
}
