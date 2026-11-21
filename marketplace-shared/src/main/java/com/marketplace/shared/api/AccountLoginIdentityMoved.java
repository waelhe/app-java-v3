package com.marketplace.shared.api;

/**
 * CodeRabbit agent findings (2026-10-26 14:33 export, row 7, CWE-613 — the
 * login-identity move's surviving-session gap): published by the identity
 * module inside the transaction that retires a login subject, the domain
 * fact that the account's login identity moved off that subject — the
 * login row now answers under a new address and the retired subject's
 * issued OAuth grants are revoked.
 *
 * <p>The gap this fact closes: the move deletes the retired subject's
 * grants, but a live browser session authenticated under the RETIRED
 * subject keeps its cached principal and authorities, and can mint fresh
 * authorization codes through the surviving session — re-creating under
 * the retired name exactly the grants the move just revoked, with the
 * cached authorities answering as if the account still carried them.
 * Spring Security's own documented basis: an authenticated session
 * retains its security context across a separate identity change until
 * the session is expired or invalidated.
 *
 * <p>The Modulith house pattern ({@code AccountCredentialsRotated}
 * precedent — the R8 wave): stored values only, published inside the
 * command's transaction so the publication registry entry commits
 * atomically with the identity write — a consumer failing AFTER_COMMIT
 * leaves the registry entry incomplete for the framework's resubmission
 * instead of losing the fact.
 *
 * <p>{@code username} is the single field by design: it is the RETIRED
 * subject's principal name — the name the session index is built on
 * (Spring Session's {@code PrincipalNameIndexResolver} indexes every
 * saved session by {@code authentication.getName()}) — so the expiry
 * consumer reaches every session the retired identity authenticated. The
 * one value every consumer of this fact needs, and the one value the
 * publishing surface holds without a query back into the account store.
 */
public record AccountLoginIdentityMoved(
        String username
) {
}
