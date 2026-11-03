package com.marketplace.app.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The purge retention's own law (the security review 5748072479's proof,
 * 2026-11-03): the cleanup's cutoff rides the family's absolute session
 * bound — {@code marketplace.security.token.refresh-family-max-age-seconds}
 * — the SAME cap the rotation law enforces, never the refresh token TTL.
 *
 * <p>A plain unit proof, no Spring context: the job is a plain
 * {@code @Component} whose constructor takes the store and the configured
 * family max age; a capturing fake store records the cutoff the scheduled
 * method hands down, and the assertions pin the retention horizon against
 * wall-clock tolerance (the job reads {@link Instant#now()} directly, so
 * the proof allows the scheduling latency the fixed-delay model permits).
 * The suite that never exercised the purge job itself was the gap this
 * test closes — the fake in {@code PlatformTokenGrantServiceTest} said so
 * out loud.</p>
 */
class ConsumedRefreshTokenCleanupJobTest {

    /** Captures the cutoff (and nothing else) — the retention's whole contract. */
    private static final class CapturingStore implements RefreshTokenRotationStore {

        private final List<Instant> cutoffs = new ArrayList<>();

        @Override
        public boolean rotate(
                org.springframework.security.oauth2.server.authorization.OAuth2Authorization rotated,
                String presentedRefreshToken) {
            throw new UnsupportedOperationException("the purge proof never rotates");
        }

        @Override
        public boolean revokeFamilyIfConsumed(String presentedRefreshToken) {
            throw new UnsupportedOperationException("the purge proof never revokes");
        }

        @Override
        public int purgeConsumedBefore(Instant cutoff) {
            cutoffs.add(cutoff);
            return 0;
        }
    }

    private static final long TOLERANCE_SECONDS = 30;

    @Test
    void theDefaultRetentionRidesTheFamilyAbsoluteBoundNotTheTokenTtl() {
        CapturingStore store = new CapturingStore();
        // The production default (2592000s = 30d), the same literal the
        // @Value fallback carries — NOT the 14d refresh TTL.
        ConsumedRefreshTokenCleanupJob job =
                new ConsumedRefreshTokenCleanupJob(store, 2_592_000L);

        Instant before = Instant.now();
        job.purgeExpiredConsumedTokens();
        Instant after = Instant.now();

        assertThat(store.cutoffs).hasSize(1);
        Instant cutoff = store.cutoffs.get(0);
        // The cutoff is now - 30d (within the run's own wall-clock span)…
        assertThat(cutoff).isAfterOrEqualTo(before.minusSeconds(2_592_000L + TOLERANCE_SECONDS));
        assertThat(cutoff).isBeforeOrEqualTo(after.minusSeconds(2_592_000L - TOLERANCE_SECONDS));
        // …and strictly older than the superseded TTL horizon (14d): the
        // family-revocation window the old rule silently deleted.
        assertThat(cutoff)
                .as("the retention must outlive the refresh TTL horizon")
                .isBefore(Instant.now().minus(PlatformTokenGrantService.REFRESH_TOKEN_TTL));
    }

    @Test
    void theConfiguredFamilyBoundDrivesTheCutoffVerbatim() {
        CapturingStore store = new CapturingStore();
        // A configured bound far from every default: the cutoff must
        // follow the configuration, proving the property is actually wired
        // (INV-2 — explicit settings, no hidden constants).
        ConsumedRefreshTokenCleanupJob job =
                new ConsumedRefreshTokenCleanupJob(store, 86_400L);

        Instant before = Instant.now();
        job.purgeExpiredConsumedTokens();
        Instant after = Instant.now();

        assertThat(store.cutoffs).hasSize(1);
        Instant cutoff = store.cutoffs.get(0);
        assertThat(cutoff).isAfterOrEqualTo(before.minusSeconds(86_400L + TOLERANCE_SECONDS));
        assertThat(cutoff).isBeforeOrEqualTo(after.minusSeconds(86_400L - TOLERANCE_SECONDS));
    }
}
