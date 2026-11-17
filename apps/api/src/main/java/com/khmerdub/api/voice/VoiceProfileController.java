package com.khmerdub.api.voice;

import com.khmerdub.api.voice.VoiceProfileService.CreateVoiceRequest;
import com.khmerdub.api.voice.VoiceProfileService.UpdateVoiceRequest;
import com.khmerdub.api.voice.VoiceProfileService.VoiceView;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The minimal local voice library (no TTS, no provider, no audio preview yet). */
@RestController
@RequestMapping("/api/voice-profiles")
public class VoiceProfileController {

    private final VoiceProfileService voices;

    public VoiceProfileController(VoiceProfileService voices) {
        this.voices = voices;
    }

    @GetMapping
    public List<VoiceView> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return voices.list(includeInactive);
    }

    @GetMapping("/{id}")
    public VoiceView get(@PathVariable UUID id) {
        return voices.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VoiceView create(@Valid @RequestBody CreateVoiceRequest request) {
        return voices.create(request);
    }

    @PatchMapping("/{id}")
    public VoiceView update(@PathVariable UUID id, @Valid @RequestBody UpdateVoiceRequest request) {
        return voices.update(id, request);
    }

    /** Deactivates the profile (it is never deleted, so speaker assignments stay intact). */
    @DeleteMapping("/{id}")
    public VoiceView deactivate(@PathVariable UUID id,
                                @RequestParam @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime expectedUpdatedAt) {
        return voices.deactivate(id, expectedUpdatedAt);
    }
}
