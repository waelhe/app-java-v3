package com.marketplace.app.graphql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The reuse-detection ledger's bounded lifetime — the shard review's
 * nitpick (Trivial · Performance & Scalability, 2026-11-01 wave 2): every
 * completed rotation adds a {@code consumed_refresh_tokens} row (V296),
 * and the ON DELETE CASCADE only fires when the family's authorization
 * row dies — an ACTIVE family rotates forever (each rotation extends the
 * family by the refresh TTL), so a session rotating every 15 minutes adds
 * ~96 rows a day with no natural bound.
 *
 * <p>The cleanup deletes rows whose reuse-detection meaning has expired.
 * The retention horizon is the family's own absolute session bound —
 * {@code marketplace.security.token.refresh-family-max-age-seconds}
 * (default 30d), the SAME cap the rotation law enforces at
 * {@link PlatformTokenGrantService#refresh} (a family older than the cap
 * is refused rotation), NOT the refresh TTL: a family outlives any single
 * consumed value by rotating (the successor minted at the presented
 * value's consumption can itself rotate for as long as the family is
 * under the cap), so a row deleted at the token TTL's horizon would
 * silently disable {@code revokeFamilyIfConsumed}'s late-replay theft
 * revocation while the family is still alive — the security review's
 * exact finding (CodeRabbit 5748072479, Minor · Security &amp; Privacy,
 * 2026-11-03). A consumed value's row therefore matters until its
 * family's absolute bound has passed; after that the rotation law itself
 * refuses the family, so a replay dies at {@code findByToken} as an
 * unknown token long before the ledger row matters. V297's index on
 * {@code consumed_at} serves the predicate (its historical migration
 * comment describes the superseded TTL rule — applied Flyway migrations
 * are immutable, so the correction lives here, not in V297).</p>
 *
 * <p>Spring Boot's own scheduling model (docs.spring.io/spring-boot —
 * Task Execution and Scheduling): {@code @EnableScheduling} on the
 * application class activates the {@code @Scheduled} methods; the fixed
 * delay keeps runs off the clock-alignment path and never overlaps the
 * previous run.</p>
 */
@Component
public class ConsumedRefreshTokenCleanupJob {

    private static final Logger log =
            LoggerFactory.getLogger(ConsumedRefreshTokenCleanupJob.class);

    private final RefreshTokenRotationStore store;
    private final Duration retention;

    public ConsumedRefreshTokenCleanupJob(
            RefreshTokenRotationStore store,
            @Value("${marketplace.security.token.refresh-family-max-age-seconds:2592000}")
            long refreshFamilyMaxAgeSeconds) {
        this.store = store;
        this.retention = Duration.ofSeconds(refreshFamilyMaxAgeSeconds);
    }

    /**
     * Hourly, an hour after the previous run's completion: the purge's
     * cutoff rides the family's absolute session bound (the same cap the
     * rotation law enforces), so the cadence only affects how long inert
     * rows linger — never the ledger's detection window.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void purgeExpiredConsumedTokens() {
        // The 2026-11-05 #806 round-3 review (Major): the cutoff is
        // computed INSIDE the database against the same clock that wrote
        // consumed_at - a host clock ahead of the database could
        // otherwise purge a row before its family's reuse-detection
        // meaning expired, silently disarming the late-replay detection
        // for that value.
        int purged = store.purgeConsumedOlderThan(retention);
        if (purged > 0) {
            // The count only — never a token hash, never a family id (the
            // ledger's own privacy posture).
            log.info("consumed_refresh_tokens cleanup: {} expired row(s) purged", purged);
        }
    }
}
