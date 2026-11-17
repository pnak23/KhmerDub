package com.khmerdub.api.system;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemMlController {

    private final MlStatusService status;

    public SystemMlController(MlStatusService status) {
        this.status = status;
    }

    /** Verified models, the ML worker's self-report (offline, isolation) and keep-awake. No paths or secrets. */
    @GetMapping("/api/system/ml")
    public Map<String, Object> ml() {
        return status.status();
    }
}
