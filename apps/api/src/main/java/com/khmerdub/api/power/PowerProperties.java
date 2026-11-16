package com.khmerdub.api.power;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Keep-awake settings ({@code khmerdub.power.*}). See {@link PowerKeeper} for the exact rules.
 *
 * The decision is re-evaluated every 5 s (a release is sent within 5 s of the decision changing).
 *
 * @param renewInterval    how often a held request is renewed
 * @param ttl              the Windows side drops the request if it is not renewed within this time
 * @param recoveryMargin   extra time after a worker's lease expires before giving up on recovery
 * @param stallLimit       RUNNING without any progress for this long counts as stuck
 * @param pausingMax       longest hold while a pause waits for the running chunk
 * @param cancellingMax    longest hold while a cancellation finishes
 * @param maxHold          absolute limit for one run of a job
 */
@Validated
@ConfigurationProperties(prefix = "khmerdub.power")
public record PowerProperties(
        boolean enabled,
        @NotNull Duration renewInterval,
        @NotNull Duration ttl,
        @NotNull Duration recoveryMargin,
        @NotNull Duration stallLimit,
        @NotNull Duration pausingMax,
        @NotNull Duration cancellingMax,
        @NotNull Duration maxHold) {
}
