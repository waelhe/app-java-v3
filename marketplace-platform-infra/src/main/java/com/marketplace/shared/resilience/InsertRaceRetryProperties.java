package com.marketplace.shared.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The bounded-retry knobs of the insert-race recovery (roadmap gap G-RETRY-1).
 *
 * <p>Bound to {@code marketplace.resilience.insert-race-retry.*} — consumed by
 * {@link InsertRaceRetryConfiguration}'s programmatic Resilience4j
 * {@code RetryConfig}. Defaults: three attempts, 100 ms wait with a ×2
 * exponential multiplier. A lost insert race is resolved by PostgreSQL
 * semantics on the FIRST retry (the winner has committed by the time the
 * loser's constraint violation surfaces), so the extra attempts only cover a
 * second concurrent writer inserting in the same window; the wait keeps that
 * retry off the hot path of the aborted transaction.
 *
 * <p>Official reference: resilience4j "How to create a RetryConfig programmatically"
 * (<a href="https://resilience4j.readme.io/docs/retry">resilience4j.readme.io/docs/retry</a>).
 */
@ConfigurationProperties(prefix = "marketplace.resilience.insert-race-retry")
public record InsertRaceRetryProperties(int maxAttempts, Duration waitDuration, double multiplier) {

    public InsertRaceRetryProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "marketplace.resilience.insert-race-retry.max-attempts must be >= 1, got " + maxAttempts);
        }
        if (waitDuration == null || waitDuration.isNegative() || waitDuration.isZero()) {
            throw new IllegalArgumentException(
                    "marketplace.resilience.insert-race-retry.wait-duration must be positive, got " + waitDuration);
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException(
                    "marketplace.resilience.insert-race-retry.multiplier must be >= 1, got " + multiplier);
        }
    }
}
