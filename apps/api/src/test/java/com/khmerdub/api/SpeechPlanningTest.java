package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.khmerdub.api.job.SpeechStagePlannerAccess;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Chunk layouts (pure functions, no Spring). */
class SpeechPlanningTest {

    @Test
    void diarizationUsesExactTenMinuteChunksWithSixtySecondOverlap() {
        // The synthetic 29-minute verification file: clip1 + clip2 + clip1 + clip2 = 1,731,402 ms.
        List<long[]> w = SpeechPlannerAccessHelper.diarization(1_731_402, 600_000, 60_000);
        assertThat(w).extracting(a -> a[0]).containsExactly(0L, 540_000L, 1_080_000L, 1_620_000L);
        assertThat(w).extracting(a -> a[1]).containsExactly(600_000L, 1_140_000L, 1_680_000L, 1_731_402L);

        List<long[]> movie = SpeechPlannerAccessHelper.diarization(10_800_000, 600_000, 60_000);
        assertThat(movie).hasSize(20);
        for (int i = 1; i < movie.size(); i++) {
            assertThat(movie.get(i - 1)[1] - movie.get(i)[0]).isEqualTo(60_000);
        }
        assertThat(movie.getLast()[1]).isEqualTo(10_800_000);
    }

    @Test
    void transcriptionCoresAreContiguousCoverEverythingAndPreferSilence() {
        List<long[]> regions = List.of(new long[] {0, 290_000}, new long[] {298_000, 10_800_000});
        List<long[]> cores = SpeechPlannerAccessHelper.cores(regions, 10_800_000, 300_000);
        assertThat(cores.getFirst()[1]).isEqualTo(294_000); // middle of the 290-298 s silence
        assertThat(cores.getFirst()[0]).isZero();
        assertThat(cores.getLast()[1]).isEqualTo(10_800_000);
        for (int i = 1; i < cores.size(); i++) {
            assertThat(cores.get(i)[0]).isEqualTo(cores.get(i - 1)[1]);
            assertThat(cores.get(i)[1] - cores.get(i)[0]).isBetween(60_000L, 400_000L);
        }
    }

    /** Access to the package-private planner functions. */
    static final class SpeechPlannerAccessHelper {
        static List<long[]> diarization(long d, long size, long overlap) {
            return SpeechStagePlannerAccess.diarizationChunks(d, size, overlap);
        }

        static List<long[]> cores(List<long[]> regions, long d, long target) {
            return SpeechStagePlannerAccess.transcriptionCores(regions, d, target);
        }
    }
}
