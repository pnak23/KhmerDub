package com.khmerdub.api.health;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Pushes system health to /topic/system/health so the UI updates without polling. */
@Component
public class HealthBroadcaster {

    public static final String TOPIC = "/topic/system/health";

    private final SystemHealthService healthService;
    private final SimpMessagingTemplate messaging;

    public HealthBroadcaster(SystemHealthService healthService, SimpMessagingTemplate messaging) {
        this.healthService = healthService;
        this.messaging = messaging;
    }

    @Scheduled(initialDelay = 5000, fixedDelayString = "${khmerdub.health-broadcast-interval-ms:15000}")
    public void broadcast() {
        messaging.convertAndSend(TOPIC, healthService.check());
    }
}
