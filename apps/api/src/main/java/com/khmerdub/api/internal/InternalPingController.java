package com.khmerdub.api.internal;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal callback surface for the Python workers (token-protected by InternalTokenFilter).
 * M1 only exposes a ping, so workers can check reachability and authentication.
 * Result and progress callbacks are added in M2.
 */
@RestController
@RequestMapping("/internal")
public class InternalPingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true, "service", "khmerdub-api", "time", Instant.now().toString());
    }
}
