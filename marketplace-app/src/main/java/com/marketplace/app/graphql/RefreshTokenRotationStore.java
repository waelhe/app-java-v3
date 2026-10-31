package com.marketplace.app.graphql;

import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;

/**
 * The refresh rotation's <b>atomic consumption boundary</b> — the seam the
 * platform's one-time-use guarantee actually rests on (the integrator's
 * multi-instance round on PR #696): the presented refresh token value is
 * consumed <b>in the authorization store itself</b>, under the database's
 * own row lock, never in JVM memory — so the guarantee holds across ANY
 * number of application instances sharing the same
 * {@code oauth2_authorization} store.
 *
 * <p>The production implementation is
 * {@link JdbcRefreshTokenRotationStore}: one conditional
 * {@code UPDATE oauth2_authorization SET refresh_token_value = :successor
 * WHERE id = :row AND refresh_token_value = :presented} whose
 * affected-rows count is the consumption verdict. Two concurrent
 * rotations of the same token — one request per instance — cannot both
 * match the row: the database serializes the two UPDATEs, the first mover
 * flips the value, the second matches zero rows and answers
 * {@code false}. No JVM lock participates in the guarantee (the
 * instance-local stripe locks were removed in the same round precisely
 * because they could not cross instances — a local lock must never be
 * adopted as an implicit distributed lock).</p>
 *
 * <p>Contract for callers ({@code PlatformTokenGrantService.refresh}):</p>
 * <ul>
 *   <li>{@code true} — THIS call won the consumption: the row still
 *       carried the presented value and now carries the successor; the
 *       rotated authorization (the successor pair riding the SAME row) is
 *       persisted.</li>
 *   <li>{@code false} — the presented value was already consumed (a
 *       replay after a completed rotation, or a concurrent rotation that
 *       won on ANY instance): the caller MUST reject the request exactly
 *       like an unknown token. Nothing was written.</li>
 * </ul>
 */
public interface RefreshTokenRotationStore {

    /**
     * Atomically consumes the presented refresh token value and persists
     * the rotated authorization.
     *
     * @param rotated the rotated authorization (the SAME row id riding
     *                the successor access/refresh pair)
     * @param presentedRefreshToken the refresh token value the caller
     *                              presented (the value being consumed)
     * @return the consumption verdict — {@code true} only for the single
     *         rotation that consumed the value
     */
    boolean rotate(OAuth2Authorization rotated, String presentedRefreshToken);
}
