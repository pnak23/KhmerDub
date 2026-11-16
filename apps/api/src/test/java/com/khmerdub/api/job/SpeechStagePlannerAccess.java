package com.khmerdub.api.job;

import java.util.List;

/** Test access to package-private planning functions of {@link SpeechStagePlanner}. */
public final class SpeechStagePlannerAccess {

    private SpeechStagePlannerAccess() {
    }

    public static List<long[]> diarizationChunks(long duration, long size, long overlap) {
        return SpeechStagePlanner.diarizationChunks(duration, size, overlap);
    }

    public static List<long[]> transcriptionCores(List<long[]> regions, long duration, long target) {
        return SpeechStagePlanner.transcriptionCores(regions, duration, target);
    }
}
