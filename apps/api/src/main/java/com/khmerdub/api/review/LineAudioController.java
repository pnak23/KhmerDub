package com.khmerdub.api.review;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LineAudioController {

    private final LineAudioService audio;

    public LineAudioController(LineAudioService audio) {
        this.audio = audio;
    }

    /** At most 60 s of the project's audio as WAV: ?fromMs=&toMs= or ?segmentId= (the line plus 250 ms padding). */
    @GetMapping("/api/projects/{projectId}/audio")
    public ResponseEntity<byte[]> slice(@PathVariable UUID projectId, @RequestParam(required = false) Long fromMs,
                                        @RequestParam(required = false) Long toMs,
                                        @RequestParam(required = false) UUID segmentId) {
        byte[] wav = audio.slice(projectId, fromMs, toMs, segmentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("line.wav").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(wav);
    }
}
