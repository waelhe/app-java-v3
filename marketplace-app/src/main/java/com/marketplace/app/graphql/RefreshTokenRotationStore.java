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

    /**
     * The reuse-detection boundary (the OAuth 2.0 Security BCP's
     * refresh-token-rotation demand, RFC 9700 §2.4.2): a refresh token
     * value the caller PRESENTED that the authorization row no longer
     * carries may be a value an earlier rotation already CONSUMED —
     * the theft shape. The store consults its family ledger (V296):
     * <ul>
     *   <li><b>Recognized, consumed beyond the grace window</b> — the
     *       LATE replay (the theft shape: the attacker rotated first,
     *       the legitimate client replays the stolen value minutes
     *       later): the ENTIRE token family is revoked (the
     *       {@code oauth2_authorization} row — access token, successor
     *       refresh tokens and all; the ledger rows cascade with it)
     *       and a security event is logged (family id only: never a
     *       token value). Answers {@code true}.</li>
     *   <li><b>Recognized, consumed within the grace window</b> — the
     *       CONCURRENT double-spend shape (two tabs racing the same
     *       refresh; the loser's rejection lands milliseconds after the
     *       winner's commit) or an immediate replay: NOTHING is
     *       written; the caller's plain rejection is the whole outcome.
     *       Answers {@code false}.</li>
     *   <li><b>Not recognized</b> (an unknown or never-consumed value):
     *       NOTHING is written; answers {@code false}.</li>
     * </ul>
     *
     * <p><b>Why the caller consults this on the resolution-failure
     * path:</b> a completed rotation REPLACES the presented value in
     * the shared row, so a replayed consumed value can only ever
     * surface as {@code findByToken(presented) == null} — it never
     * reaches {@link #rotate} (only a concurrent double-spend does,
     * whose loser leg detects reuse the same way). The check is
     * therefore wired at BOTH rejection sites: the store's own
     * {@code rotate == false} verdict, and the caller's
     * failed-resolution branch. The rejection the caller raises is
     * IDENTICAL in every case — the caller must never reveal whether
     * the ledger recognized the value.</p>
     *
     * @param presentedRefreshToken the refresh token value the caller
     *                              presented (whose consumed state is
     *                              in question)
     * @return {@code true} only when a stale consumed value was
     *         recognized AND its family revoked
     */
    boolean revokeFamilyIfConsumed(String presentedRefreshToken);

    /**
     * The ledger's bounded lifetime (the shard review's nitpick,
     * 2026-11-01 wave 2): deletes the consumed-value rows whose
     * reuse-detection meaning has expired — a consumed value matters only
     * while the successor it was replaced by can still be alive, so a row
     * older than the refresh TTL past its consumption is inert (a replay
     * of that value dies at {@code findByToken} as an unknown token long
     * before the ledger row matters). The ON DELETE CASCADE cannot bound
     * the table on its own: an ACTIVE family rotates forever, and every
     * rotation adds a row.
     *
     * @param cutoff rows with {@code consumed_at} strictly before this
     *               instant are deleted
     * @return the number of rows deleted
     */
    int purgeConsumedBefore(java.time.Instant cutoff);
}
