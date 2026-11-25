package com.marketplace.app.graphql;

import static org.assertj.core.api.Assertions.assertThat;

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
 * family max age; a capturing fake store records the RETENTION the
 * scheduled method hands down. The 2026-11-05 #806 round-3 review moved
 * the cutoff arithmetic into the database (the same clock that wrote
 * {@code consumed_at} - a host clock ahead of the DB could otherwise
 * purge a row before its reuse-detection meaning expired), so the job
 * passes the retention through VERBATIM and there is no wall-clock
 * tolerance left to allow.</p>
 */
class ConsumedRefreshTokenCleanupJobTest {

    /** Captures the retention (and nothing else) - the whole contract. */
    private static final class CapturingStore implements RefreshTokenRotationStore {

        private final List<java.time.Duration> retentions = new ArrayList<>();

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
        public int purgeConsumedOlderThan(java.time.Duration retention) {
            retentions.add(retention);
            return 0;
        }
    }

    @Test
    void theDefaultRetentionRidesTheFamilyAbsoluteBoundNotTheTokenTtl() {
        CapturingStore store = new CapturingStore();
        // The production default (2592000s = 30d), the same literal the
        // @Value fallback carries — NOT the 14d refresh TTL.
        ConsumedRefreshTokenCleanupJob job =
                new ConsumedRefreshTokenCleanupJob(store, 2_592_000L);

        job.purgeExpiredConsumedTokens();

        // The retention passes through VERBATIM (the DB computes the
        // cutoff against its own clock)…
        assertThat(store.retentions).containsExactly(java.time.Duration.ofSeconds(2_592_000L));
        // …and still outlives the superseded TTL horizon (14d): the
        // family-revocation window the old rule silently deleted.
        assertThat(store.retentions.getFirst().toSeconds())
                .as("the retention must outlive the refresh TTL horizon")
                .isGreaterThan(PlatformTokenGrantService.REFRESH_TOKEN_TTL.toSeconds());
    }

    @Test
    void theConfiguredFamilyBoundDrivesTheCutoffVerbatim() {
        CapturingStore store = new CapturingStore();
        // A configured bound far from every default: the cutoff must
        // follow the configuration, proving the property is actually wired
        // (INV-2 — explicit settings, no hidden constants).
        ConsumedRefreshTokenCleanupJob job =
                new ConsumedRefreshTokenCleanupJob(store, 86_400L);

        job.purgeExpiredConsumedTokens();

        assertThat(store.retentions).containsExactly(java.time.Duration.ofSeconds(86_400L));
    }
}
