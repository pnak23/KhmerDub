package com.khmerdub.api.job;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Enums of the job engine. Names match the database CHECK constraints (see V1/V2 migrations). */
public final class JobEnums {

    private JobEnums() {
    }

    public enum JobType {
        IMPORT(false, List.of(Stage.INSPECT_MEDIA)),
        EXTRACT_AUDIO(true, List.of(Stage.EXTRACT_AUDIO, Stage.MERGE_AUDIO)),
        /** Speech map, language, transcription, speaker identification, cross-chunk speakers, dialogue (M3b). */
        TRANSCRIBE(true, List.of(Stage.SPEECH_MAP, Stage.DETECT_LANGUAGE, Stage.TRANSCRIBE, Stage.DIARIZE,
                Stage.RECONCILE_SPEAKERS, Stage.BUILD_DIALOGUE)),
        TRANSLATE(true, List.of()),
        GENERATE_AUDIO(true, List.of()),
        REGENERATE_CLIPS(false, List.of()),
        EXPORT(true, List.of()),
        FULL_PIPELINE(true, List.of());

        /** Heavy jobs process a whole movie; only one heavy job runs at a time. */
        private final boolean heavy;
        private final List<Stage> stages;

        JobType(boolean heavy, List<Stage> stages) {
            this.heavy = heavy;
            this.stages = stages;
        }

        public boolean heavy() { return heavy; }
        public List<Stage> stages() { return stages; }
    }

    public enum JobStatus {
        QUEUED, RUNNING, PAUSING, PAUSED, CANCELLING, COMPLETED, FAILED, CANCELLED;

        public static final Set<JobStatus> ACTIVE = EnumSet.of(QUEUED, RUNNING, PAUSING, PAUSED, CANCELLING);
        /** Statuses that occupy the single heavy-job slot. */
        public static final Set<JobStatus> OCCUPYING = EnumSet.of(RUNNING, PAUSING, CANCELLING);

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    public enum Stage {
        INSPECT_MEDIA, EXTRACT_AUDIO, MERGE_AUDIO, SPEECH_MAP, DETECT_LANGUAGE, TRANSCRIBE, DIARIZE, RECONCILE_SPEAKERS,
        BUILD_DIALOGUE, TRANSLATE, TTS, ASSEMBLE, MIX, SUBTITLE, EXPORT, VALIDATE
    }

    public enum ChunkStatus {
        PENDING, DISPATCHED, RUNNING, COMPLETED, FAILED, CANCELLED, SKIPPED;

        public static final Set<ChunkStatus> LIVE = EnumSet.of(DISPATCHED, RUNNING);

        public boolean done() {
            return this == COMPLETED || this == SKIPPED;
        }
    }

    public enum AttemptStatus {
        DISPATCHED, RUNNING, SUCCEEDED, FAILED, LOST, CANCELLED;

        public static final Set<AttemptStatus> LIVE = EnumSet.of(DISPATCHED, RUNNING);

        public boolean live() {
            return LIVE.contains(this);
        }
    }

    public enum EndReason {
        COMPLETED, WORKER_ERROR, INVALID_INPUT, LEASE_EXPIRED, CLAIM_TIMEOUT, REDELIVERED, DISPATCH_FAILED, CANCELLED,
        JOB_FAILED
    }
}
