package com.khmerdub.api.health;

/** UP = working; WARN = working but needs attention (e.g. low disk); DOWN = not working. */
public enum HealthStatus {
    UP, WARN, DOWN;

    static HealthStatus parse(Object value) {
        if (value == null) {
            return DOWN;
        }
        return switch (value.toString().toUpperCase()) {
            case "UP" -> UP;
            case "WARN" -> WARN;
            default -> DOWN;
        };
    }
}
