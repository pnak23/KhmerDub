package com.khmerdub.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.khmerdub.api.power.KeepAwakePolicy;
import com.khmerdub.api.power.KeepAwakePolicy.JobSnapshot;
import com.khmerdub.api.power.PowerProperties;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exact keep-awake boundaries (lease 60 s, recovery margin 120 s, stall 30 min, pausing 20 min, cancelling 3 min). */
class KeepAwakePolicyTest {

    static final Duration LEASE = Duration.ofSeconds(60);
    static final PowerProperties P = new PowerProperties(true, Duration.ofSeconds(30), Duration.ofSeconds(120),
            Duration.ofSeconds(120), Duration.ofMinutes(30), Duration.ofMinutes(20), Duration.ofMinutes(3),
            Duration.ofHours(24));
    static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 13, 12, 0, 0, 0, ZoneOffset.UTC);
    static final UUID JOB = UUID.randomUUID();

    static boolean hold(JobSnapshot... jobs) {
        return KeepAwakePolicy.decide(NOW, List.of(jobs), LEASE, P).hold();
    }

    static JobSnapshot running(long startedAgoS, long signalAgoS, long progressAgoS) {
        return new JobSnapshot(JOB, "RUNNING", NOW.minusSeconds(startedAgoS), null, NOW.minusSeconds(signalAgoS),
                NOW.minusSeconds(progressAgoS), 1);
    }

    @Test
    void runningHoldsWhileTheWorkerSignalIsWithinLeasePlusRecoveryMargin() {
        assertThat(hold(running(3600, 10, 10))).isTrue();
        assertThat(hold(running(3600, 180, 10))).as("exactly 60 + 120 s").isTrue();
        assertThat(hold(running(3600, 181, 10))).as("stale heartbeat, lease expired, no recovery").isFalse();
    }

    @Test
    void aFreshlyStartedRunGetsTheSameWindowBeforeAnyWorkerSignal() {
        JobSnapshot j = new JobSnapshot(JOB, "RUNNING", NOW.minusSeconds(100), null, null, null, 1);
        assertThat(hold(j)).isTrue();
        JobSnapshot late = new JobSnapshot(JOB, "RUNNING", NOW.minusSeconds(181), null, null, null, 1);
        assertThat(hold(late)).as("worker never came").isFalse();
    }

    @Test
    void aRunningJobWithoutProgressForThirtyMinutesIsStuck() {
        assertThat(hold(running(7200, 5, 29 * 60))).isTrue();
        assertThat(hold(running(7200, 5, 30 * 60 + 1))).as("heartbeats alone do not keep the PC awake").isFalse();
    }

    @Test
    void neverLongerThanTheAbsoluteLimit() {
        assertThat(hold(running(24 * 3600 - 1, 5, 5))).isTrue();
        assertThat(hold(running(24 * 3600 + 1, 5, 5))).isFalse();
    }

    @Test
    void pausingHoldsAtMostTwentyMinutesAndOnlyWhileTheChunkIsAlive() {
        JobSnapshot ok = new JobSnapshot(JOB, "PAUSING", NOW.minusHours(1), NOW.minusMinutes(19), NOW.minusSeconds(5), NOW, 1);
        JobSnapshot tooLong = new JobSnapshot(JOB, "PAUSING", NOW.minusHours(1), NOW.minusMinutes(20).minusSeconds(1),
                NOW.minusSeconds(5), NOW, 1);
        JobSnapshot silent = new JobSnapshot(JOB, "PAUSING", NOW.minusHours(1), NOW.minusMinutes(2), NOW.minusSeconds(181), NOW, 1);
        JobSnapshot nothingLive = new JobSnapshot(JOB, "PAUSING", NOW.minusHours(1), NOW.minusMinutes(2), NOW.minusSeconds(5), NOW, 0);
        assertThat(hold(ok)).isTrue();
        assertThat(hold(tooLong)).isFalse();
        assertThat(hold(silent)).isFalse();
        assertThat(hold(nothingLive)).isFalse();
    }

    @Test
    void cancellingHoldsAtMostThreeMinutes() {
        JobSnapshot ok = new JobSnapshot(JOB, "CANCELLING", NOW.minusHours(1), NOW.minusSeconds(179), NOW.minusSeconds(200), NOW, 1);
        JobSnapshot late = new JobSnapshot(JOB, "CANCELLING", NOW.minusHours(1), NOW.minusSeconds(181), NOW, NOW, 1);
        assertThat(hold(ok)).isTrue();
        assertThat(hold(late)).isFalse();
    }

    @Test
    void otherStatesAndNoJobsNeverHold() {
        assertThat(hold()).isFalse();
        for (String s : List.of("QUEUED", "PAUSED", "COMPLETED", "FAILED", "CANCELLED")) {
            assertThat(hold(new JobSnapshot(JOB, s, NOW, NOW, NOW, NOW, 1))).as(s).isFalse();
        }
    }

    @Test
    void anyActiveJobIsEnough() {
        JobSnapshot stuck = running(3600, 400, 400);
        JobSnapshot fine = running(60, 5, 5);
        assertThat(hold(stuck, fine)).isTrue();
    }
}
