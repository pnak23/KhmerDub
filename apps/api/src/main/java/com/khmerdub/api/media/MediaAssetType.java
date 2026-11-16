package com.khmerdub.api.media;

import java.util.EnumSet;
import java.util.Set;

public enum MediaAssetType {
    ORIGINAL_VIDEO, EXTRACTED_AUDIO, AUDIO_CHUNK, TRANSCRIPT, DIARIZATION, SPEAKER_EMBEDDING, TRANSLATION, TTS_CLIP,
    VOICE_PREVIEW, BACKGROUND_AUDIO, DUB_AUDIO, MIXED_AUDIO, SUBTITLE, EXPORT_VIDEO, OTHER,
    SPEECH_MAP, SPEAKER_SAMPLE, DIALOGUE, DIALOGUE_BACKUP;

    /**
     * Biometric-like data: never served, listed or described by any API (not even metadata). Stored only under
     * projects/{id}/private/fingerprints/ (enforced by a database CHECK).
     */
    public static final Set<MediaAssetType> NEVER_EXPOSED = EnumSet.of(SPEAKER_EMBEDDING);

    public boolean exposable() {
        return !NEVER_EXPOSED.contains(this);
    }
}
