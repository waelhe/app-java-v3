package com.marketplace.shared.api;

/**
 * CodeRabbit agent findings (2026-10-26 14:33 export, row 13, CWE-613 —
 * the sign-out-everywhere surviving-session gap): published by the
 * identity module inside the transaction that revokes a principal's
 * stored OAuth authorizations at the owner's explicit request, the
 * domain fact that the account's sessions were revoked wholesale by the
 * owner — "sign out everywhere".
 *
 * <p>The gap this fact closes: the revocation deletes the stored
 * authorization rows, but a live browser session keeps its authenticated
 * security context and can authorize again immediately — re-creating
 * through the surviving session exactly the replacement grants the
 * revocation just removed, without any credential challenge. The
 * owner's intent ("no session of mine keeps working") covers the
 * sessions as much as the stored tokens; Spring Security's own
 * documented basis: an authenticated session retains its security
 * context until the session is expired or invalidated.
 *
 * <p>The Modulith house pattern ({@code AccountCredentialsRotated}
 * precedent — the R8 wave): stored values only, published inside the
 * revocation's transaction so the publication registry entry commits
 * atomically with the authorization delete — a consumer failing
 * AFTER_COMMIT leaves the registry entry incomplete for the framework's
 * resubmission instead of losing the fact.
 *
 * <p>{@code username} is the single field by design: it is the principal
 * name the session index is built on (Spring Session's
 * {@code PrincipalNameIndexResolver} indexes every saved session by
 * {@code authentication.getName()}) — the one value the expiry consumer
 * needs, and the one value the publishing surface holds.
 */
public record AccountSessionsRevoked(
        String username
) {
}
