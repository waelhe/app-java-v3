package com.marketplace.app.graphql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The reuse-detection ledger's bounded lifetime — the shard review's
 * nitpick (Trivial · Performance & Scalability, 2026-11-01 wave 2): every
 * completed rotation adds a {@code consumed_refresh_tokens} row (V296),
 * and the ON DELETE CASCADE only fires when the family's authorization
 * row dies — an ACTIVE family rotates forever (each rotation extends the
 * family by the refresh TTL), so a session rotating every 15 minutes adds
 * ~96 rows a day with no natural bound.
 *
 * <p>The cleanup deletes rows whose reuse-detection meaning has expired:
 * a consumed value matters only while the successor it was replaced by
 * can still be alive, so a row older than the refresh TTL
 * ({@link PlatformTokenGrantService#REFRESH_TOKEN_TTL}) past its
 * consumption is inert — a replay of that value dies at
 * {@code findByToken} as an unknown token long before the ledger row
 * matters. V297's index on {@code consumed_at} serves the predicate.</p>
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

    public ConsumedRefreshTokenCleanupJob(RefreshTokenRotationStore store) {
        this.store = store;
    }

    /**
     * Hourly, an hour after the previous run's completion: the purge's
     * cutoff rides the refresh TTL (the row's own expiry semantics), so
     * the cadence only affects how long inert rows linger — never the
     * ledger's detection window.
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT10M")
    public void purgeExpiredConsumedTokens() {
        Instant cutoff = Instant.now().minus(PlatformTokenGrantService.REFRESH_TOKEN_TTL);
        int purged = store.purgeConsumedBefore(cutoff);
        if (purged > 0) {
            // The count only — never a token hash, never a family id (the
            // ledger's own privacy posture).
            log.info("consumed_refresh_tokens cleanup: {} expired row(s) purged", purged);
        }
    }
}
