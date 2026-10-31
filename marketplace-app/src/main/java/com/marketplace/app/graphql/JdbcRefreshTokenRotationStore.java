package com.marketplace.app.graphql;

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

    /**
     * V13's official table; the consumption predicate is the presented
     * refresh token value itself — the value is the concurrency token.
     */
    private static final String CONSUME_ROTATED_REFRESH_TOKEN =
            "UPDATE oauth2_authorization SET refresh_token_value = ? WHERE id = ? AND refresh_token_value = ?";

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
            // another instance. This caller did NOT consume it — reject.
            return false;
        }
        // The consumption won: complete the rotation's full row (the
        // successor pair's columns) on the same row lock, in the same
        // transaction — all-or-nothing with the consumption itself.
        authorizationService().save(rotated);
        return true;
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
