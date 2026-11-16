package com.khmerdub.api.privacy;

import com.khmerdub.api.power.PowerKeeper;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PrivacyController {

    private final PrivacyService privacy;
    private final PowerKeeper power;

    public PrivacyController(PrivacyService privacy, PowerKeeper power) {
        this.privacy = privacy;
        this.power = power;
    }

    @GetMapping("/projects/{projectId}/privacy")
    public Map<String, Object> summary(@PathVariable UUID projectId) {
        return privacy.summary(projectId);
    }

    @PostMapping("/projects/{projectId}/voice-fingerprints/forget")
    public Map<String, Object> forget(@PathVariable UUID projectId) {
        return view(privacy.forgetFingerprints(projectId));
    }

    @PostMapping("/projects/{projectId}/voice-samples/delete")
    public Map<String, Object> deleteSamples(@PathVariable UUID projectId) {
        return view(privacy.deleteVoiceSamples(projectId));
    }

    /** Keep-awake state and its rules (System page). */
    @GetMapping("/system/power")
    public Map<String, Object> power() {
        return power.status();
    }

    private static Map<String, Object> view(PrivacyAction a) {
        return Map.of("action", a.getAction(), "filesDeleted", a.getFilesDeleted(), "bytesDeleted", a.getBytesDeleted(),
                "verified", a.isVerified(), "details", a.getDetails(), "createdAt", a.getCreatedAt().toString());
    }
}
