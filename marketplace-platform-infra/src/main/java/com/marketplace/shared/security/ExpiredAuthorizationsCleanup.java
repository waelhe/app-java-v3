package com.marketplace.shared.security;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically purges fully-expired rows from {@code oauth2_authorization}.
 *
 * <p><b>Why this component exists (the gap it closes).</b> Spring Authorization
 * Server 7.1.1 has <i>no</i> automatic removal of expired authorizations: the
 * JDBC service only removes a row on an explicit
 * {@link org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService#remove(OAuth2Authorization)}
 * call (token revocation / consumed authorization code) — verified against the
 * framework sources saved at {@code scripts/verify-aud-claim/sas-all/
 * org/springframework/security/oauth2/server/authorization/JdbcOAuth2AuthorizationService.java},
 * which contains no scheduled or expiry-driven {@code DELETE}. Every login and
 * token flow inserts a row (token endpoint, refresh, device code), so the table
 * grows unbounded without application-side housekeeping — exactly the class of
 * problem the Spring Modulith reference describes for completed event
 * publications ("… the persistent abstraction of them … will grow unbounded and
 * the interaction with the store … might slow down"); this component applies the
 * same housekeeping to the authorization store.
 *
 * <p><b>Predicate (conservative).</b> A row is deleted only when <i>every</i>
 * credential the row carries is past {@code cutoff}: each of the six
 * {@code *_expires_at} columns (V13 — the SAS 7.1.1 schema layout) is either
 * {@code NULL} (that credential was never issued) or strictly older than the
 * cutoff. Rows whose columns are all {@code NULL} are left alone — they carry
 * no expirable lifecycle to evaluate. The cutoff trails {@code now} by a
 * retention window (7 days, symmetric with {@link com.marketplace.shared.cache
 * .EventPublicationCleanup}) so recently-expired authorizations stay available
 * for operational inspection, clock-skew cushioning and in-flight requests.
 *
 * <p><b>Safety.</b> An authorization whose every credential is expired is dead
 * to the framework: SAS validates expiry before honoring a refresh or
 * introspection request, so deleting such rows cannot affect any live flow.
 * No {@code V} migration is involved — this is row housekeeping on the existing
 * V13 schema, not a schema change.
 *
 * <p>Mirrors the shape of {@code EventPublicationCleanup} (same module,
 * {@code @EnableScheduling} via {@code CacheConfig}): a single daily cron at
 * 04:00 UTC — one hour after the event-publications purge so the two
 * housekeeping sweeps never contend on the scheduler thread.
 */
@Component
public class ExpiredAuthorizationsCleanup {

    private static final Logger log = LoggerFactory.getLogger(ExpiredAuthorizationsCleanup.class);

    /**
     * Retention of expired-but-unremoved authorizations before this sweep may
     * delete them. Symmetric with {@code EventPublicationCleanup}'s 7 days.
     * Public test seam: {@code ExpiredAuthorizationsCleanupIntegrationTest}
     * (different package) asserts the exact cutoff semantics.
     */
    public static final Duration RETENTION = Duration.ofDays(7);

    /**
     * A row is removable when every carried credential is expired; NULL columns
     * mean the credential was never issued and are ignored. The trailing clause
     * requires at least one issued credential, so all-NULL rows are never
     * deleted. Column names are the V13 / SAS 7.1.1 schema layout.
     */
    // @formatter:off
    static final String DELETE_FULLY_EXPIRED = """
            DELETE FROM oauth2_authorization
             WHERE (authorization_code_expires_at IS NULL OR authorization_code_expires_at < :cutoff)
               AND (access_token_expires_at      IS NULL OR access_token_expires_at      < :cutoff)
               AND (oidc_id_token_expires_at     IS NULL OR oidc_id_token_expires_at     < :cutoff)
               AND (refresh_token_expires_at     IS NULL OR refresh_token_expires_at     < :cutoff)
               AND (user_code_expires_at         IS NULL OR user_code_expires_at         < :cutoff)
               AND (device_code_expires_at       IS NULL OR device_code_expires_at       < :cutoff)
               AND (  authorization_code_expires_at IS NOT NULL
                   OR access_token_expires_at      IS NOT NULL
                   OR oidc_id_token_expires_at     IS NOT NULL
                   OR refresh_token_expires_at     IS NOT NULL
                   OR user_code_expires_at         IS NOT NULL
                   OR device_code_expires_at       IS NOT NULL)
            """;
    // @formatter:on

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ExpiredAuthorizationsCleanup(DataSource dataSource) {
        // NamedParameterJdbcTemplate over the application DataSource — the same
        // JdbcTemplate infrastructure SecurityConfig's JdbcOAuth2AuthorizationService
        // uses, so the sweep and the framework service share pool + transaction
        // semantics without constructing a second pool.
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Deletes authorizations whose every credential expired more than
     * {@link #RETENTION} ago. Runs daily at 04:00 UTC (off-peak; one hour
     * offset from the 03:00 event-publications purge).
     */
    @Scheduled(cron = "0 0 4 * * ?", zone = "UTC")
    void purgeExpiredAuthorizations() {
        int deleted = purgeExpiredBefore(Instant.now().minus(RETENTION));
        if (deleted > 0) {
            log.info("Purged {} fully-expired authorizations (older than {})", deleted, RETENTION);
        } else {
            log.debug("No fully-expired authorizations older than {} to purge", RETENTION);
        }
    }

    /**
     * The sweep, parameterized on the cutoff — the test seam
     * {@code ExpiredAuthorizationsCleanupIntegrationTest} (different package)
     * drives it with a controlled cutoff: deletes rows whose every issued
     * credential expired strictly before {@code cutoff}.
     *
     * @return number of rows deleted
     */
    public int purgeExpiredBefore(Instant cutoff) {
        return jdbcTemplate.update(DELETE_FULLY_EXPIRED,
                java.util.Map.of("cutoff", Timestamp.from(cutoff)));
    }
}
