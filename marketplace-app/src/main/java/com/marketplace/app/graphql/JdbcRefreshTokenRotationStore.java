package com.marketplace.app.graphql;

import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The production {@link RefreshTokenRotationStore}: the refresh rotation's
 * one-time-use semantics enforced <b>by the database</b> — a single
 * conditional UPDATE against the official {@code oauth2_authorization}
 * table (V13, the authorization server's own store):
 *
 * <pre>
 * UPDATE oauth2_authorization
 *    SET refresh_token_value = :successor
 *  WHERE id = :row AND refresh_token_value = :presented
 * </pre>
 *
 * <p><b>Why the affected-rows count is the atomic consumption verdict:</b>
 * the database's own row lock serializes concurrent executions of this
 * statement across EVERY application instance sharing the store. Two
 * simultaneous rotations of the same token (one request per instance)
 * both resolve the row through {@code findByToken} and both build a
 * successor — but only ONE update can match {@code refresh_token_value =
 * :presented}: the first mover flips the value and commits; the second
 * re-evaluates the predicate against the committed successor, matches
 * ZERO rows, and answers {@code false}. The loser is rejected exactly
 * like a replay — no JVM lock participates (the instance-local stripe
 * locks were removed in the same round; a local lock must never be
 * adopted as an implicit distributed lock).</p>
 *
 * <p><b>The full-row save rides the same transaction:</b> the consumption
 * and the successor's complete column set (access token value/issued-at/
 * expires-at/metadata, refresh token issued-at/expires-at/metadata —
 * everything the official row mapper reads back) commit all-or-nothing.
 * A failure between the two rolls the consumption back — the presented
 * token stays consumable, nothing is lost; a success commits the
 * successor exactly once, so {@code findByToken(successor)} resolves the
 * row the winner kept and {@code findByToken(presented)} answers
 * {@code null} ever after — the official row-replacement invalidation,
 * now enforced atomically at the store.</p>
 *
 * <p><b>Sequencing with the platform's own primitives:</b> the row is the
 * authorization server's ({@code JdbcOAuth2AuthorizationService} over
 * V13); the successor lands through the SAME
 * {@link OAuth2AuthorizationService#save} the official
 * {@code OAuth2RefreshTokenAuthenticationProvider} rotation uses — no
 * second issuer, no parallel token path, no hand-written column mapping
 * beyond the one consumption predicate this store exists to enforce.</p>
 */
@Component
public class JdbcRefreshTokenRotationStore implements RefreshTokenRotationStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(JdbcRefreshTokenRotationStore.class);

    /**
     * V13's official table; the consumption predicate is the presented
     * refresh token value itself — the value is the concurrency token.
     */
    private static final String CONSUME_ROTATED_REFRESH_TOKEN =
            "UPDATE oauth2_authorization SET refresh_token_value = ? WHERE id = ? AND refresh_token_value = ?";

    /**
     * The reuse-detection ledger (V296): every completed rotation records
     * the CONSUMED value's SHA-256 under its authorization family — the
     * oauth2_authorization row IS the family.
     */
    private static final String RECORD_CONSUMED_REFRESH_TOKEN =
            "INSERT INTO consumed_refresh_tokens (authorization_id, token_hash) VALUES (?, ?)";

    /**
     * The theft-signal predicate: a consumed value presented again MORE
     * THAN THE GRACE WINDOW after its consumption. The window (30s)
     * reconciles the two recorded contracts: a CONCURRENT double-spend
     * (two tabs racing the same refresh — the loser's rejection lands
     * milliseconds after the winner's commit) stays a plain rejection, so
     * the winner's successor keeps the platform's recorded multi-instance
     * contract alive; a LATE replay (the theft shape: the attacker rotated
     * first, the legitimate client replays the stolen value minutes later)
     * is the family-revoking signal.
     */
    private static final Duration REUSE_DETECTION_GRACE = Duration.ofSeconds(30);

    private static final String FIND_STALE_FAMILY_OF_PRESENTED = """
            SELECT authorization_id FROM consumed_refresh_tokens
            WHERE token_hash = ? AND consumed_at < now() - make_interval(secs => ?)
            """;

    /** Family revocation — one statement removes the whole token family. */
    private static final String REVOKE_FAMILY =
            "DELETE FROM oauth2_authorization WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<OAuth2AuthorizationService> authorizationService;

    public JdbcRefreshTokenRotationStore(
            JdbcTemplate jdbcTemplate,
            ObjectProvider<OAuth2AuthorizationService> authorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
    }

    @Override
    @Transactional
    public boolean rotate(OAuth2Authorization rotated, String presentedRefreshToken) {
        String successor = rotated.getRefreshToken().getToken().getTokenValue();
        int consumed = jdbcTemplate.update(CONSUME_ROTATED_REFRESH_TOKEN,
                successor, rotated.getId(), presentedRefreshToken);
        if (consumed == 0) {
            // The presented value is gone from the row: a replay after a
            // completed rotation, or a concurrent rotation that won on
            // another instance. This caller did NOT consume it — reject…
            revokeFamilyIfConsumed(presentedRefreshToken);
            return false;
        }
        // The consumption won: record the consumed value's hash in the
        // family ledger (V296) and complete the rotation's full row (the
        // successor pair's columns) on the same row lock, in the same
        // transaction — all-or-nothing with the consumption itself.
        jdbcTemplate.update(RECORD_CONSUMED_REFRESH_TOKEN,
                rotated.getId(), sha256(presentedRefreshToken));
        authorizationService().save(rotated);
        return true;
    }

    /**
     * The shard review (2026-11-01, Security & Privacy): an already-consumed
     * value presented again is a THEFT SIGNAL, not just an invalid token —
     * an attacker who rotated first left the legitimate user failing
     * silently while the attacker's successor stayed valid. When the ledger
     * recognizes the value BEYOND the grace window, the ENTIRE family is
     * revoked (the authorization row — access token, successor refresh
     * tokens and all) and a security event is logged (family id only: never
     * a token value). An unknown value — or one consumed within the grace
     * window (the concurrent double-spend shape) — stays a plain rejection.
     *
     * <p><b>Called from BOTH rejection sites:</b> the {@link #rotate}
     * loser leg (a concurrent double-spend whose winner committed on
     * another instance), and — through the interface — the grant
     * service's failed-resolution branch (a LATE replay never reaches
     * {@code rotate}: the completed rotation already replaced the
     * presented value in the shared row, so the replay surfaces as
     * {@code findByToken(presented) == null} and dies there). The grace
     * window keeps both recorded contracts intact: the concurrent
     * loser's in-window rejection revokes nothing; the late replay
     * (past the window) revokes the family.</p>
     */
    @Override
    public boolean revokeFamilyIfConsumed(String presentedRefreshToken) {
        java.util.List<String> family = jdbcTemplate.queryForList(
                FIND_STALE_FAMILY_OF_PRESENTED, String.class,
                sha256(presentedRefreshToken), REUSE_DETECTION_GRACE.toSeconds());
        if (family.isEmpty()) {
            return false;
        }
        String familyId = family.getFirst();
        int revoked = jdbcTemplate.update(REVOKE_FAMILY, familyId);
        log.warn("SECURITY refresh-token reuse detected: authorization family {} revoked"
                + " ({} authorization row(s) removed); the presented value was already"
                + " consumed by an earlier rotation", familyId, revoked);
        return revoked > 0;
    }

    /** The ledger's bounded-lifetime purge (V297's index serves the predicate). */
    @Override
    public int purgeConsumedBefore(java.time.Instant cutoff) {
        return jdbcTemplate.update(
                "DELETE FROM consumed_refresh_tokens WHERE consumed_at < ?",
                java.sql.Timestamp.from(cutoff));
    }

    /** SHA-256 of the token value — the ledger never stores a bearer secret. */
    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private OAuth2AuthorizationService authorizationService() {
        OAuth2AuthorizationService service = authorizationService.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException(
                    "The authorization service is not available — the refresh rotation requires it");
        }
        return service;
    }
}
