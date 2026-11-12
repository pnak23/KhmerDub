package com.khmerdub.api.health;

import java.time.Instant;
import java.util.Map;

public record SystemHealth(HealthStatus status, String version, Instant checkedAt,
                           Map<String, ComponentHealth> components) {

    public record ComponentHealth(HealthStatus status, Map<String, Object> details) {
    }
}
