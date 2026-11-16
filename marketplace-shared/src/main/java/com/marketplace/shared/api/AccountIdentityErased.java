package com.marketplace.shared.api;

import java.util.UUID;

/**
 * CodeRabbit agent findings (row 7, CWE-613 — the surviving-session gap of
 * account erasure): published by the identity module's pseudonymization
 * command inside its own transaction, the domain fact that the account's
 * login identity was erased — the login row deleted, the subject rewritten,
 * the issued authorizations revoked. Both erasure surfaces ride this one
 * fact: the administrative pseudonymization and the self-service deletion
 * (which delegates to the same command).
 *
 * <p>The gap this fact closes: the erasure kills the login row and the
 * refresh grants, but the sessions minted BEFORE the erasure hold their
 * authenticated principal (and its cached authorities) inside the session
 * store — a surviving session of an erased administrator keeps passing
 * console method authorization and can keep operating privileged surfaces
 * under a deleted identity.
 *
 * <p>The Modulith house pattern ({@code AccountStatusChanged} /
 * {@code UserRoleChanged} precedents — the R8 wave): identifiers and stored
 * values only, published inside the command's transaction so the
 * publication registry entry commits atomically with the store writes — a
 * consumer failing AFTER_COMMIT leaves the registry entry incomplete for
 * the framework's resubmission instead of losing the fact.
 *
 * <p>{@code username} carries the ORIGINAL login-side principal name (the
 * email subject the sessions were authenticated under — Spring Session's
 * {@code PrincipalNameIndexResolver} indexed them by exactly that name),
 * not the pseudonymized replacement: the invalidator must resolve the
 * sessions that still carry the erased identity, and no session will ever
 * be indexed under the replacement subject.
 */
public record AccountIdentityErased(
        UUID userId,
        String username
) {
}
