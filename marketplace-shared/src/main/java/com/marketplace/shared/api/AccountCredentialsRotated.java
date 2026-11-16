package com.marketplace.shared.api;

/**
 * CodeRabbit agent findings (row 14, CWE-613 — the surviving-session gap of
 * password recovery): published by the identity module's password-reset
 * completion inside its own transaction, the domain fact that the
 * account's credential was rotated by recovery — the stored verifier
 * replaced and the issued OAuth grants revoked.
 *
 * <p>The gap this fact closes: the recovery revokes the stored grants, but
 * a live browser session authenticated under the OLD password keeps its
 * cached principal and can mint fresh authorization codes — re-creating
 * through the surviving session exactly the grants the recovery just
 * revoked. Spring Security's own documented basis: an authenticated
 * session retains its security context across a separate password update
 * until the session is expired or invalidated.
 *
 * <p>The Modulith house pattern ({@code AccountStatusChanged} precedent —
 * the R8 wave): stored values only, published inside the command's
 * transaction so the publication registry entry commits atomically with
 * the credential write — a consumer failing AFTER_COMMIT leaves the
 * registry entry incomplete for the framework's resubmission instead of
 * losing the fact.
 *
 * <p>{@code username} is the single field by design: it is the login-side
 * principal name the session index is built on (Spring Session's
 * {@code PrincipalNameIndexResolver} indexes every saved session by
 * {@code authentication.getName()}) — the one value every consumer of
 * this fact needs, and the one value the publishing surface holds without
 * a query back into the account store. The event is therefore
 * self-contained for its consumer (the
 * {@code AccountStatusSessionInvalidator} in platform-infra).
 */
public record AccountCredentialsRotated(
        String username
) {
}
