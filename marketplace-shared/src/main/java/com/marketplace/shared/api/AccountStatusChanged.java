package com.marketplace.shared.api;

import java.util.UUID;

/**
 * R8 (comprehensive-review-ar fix plan §4, Wave 1): published by the identity
 * module's account-status command inside its own transaction, the domain fact
 * that an account's login-side status flipped — {@code auth_users.enabled},
 * written through the framework-managed {@code UserDetailsManager}.
 *
 * <p>The Modulith house pattern ({@code UserRoleChanged} /
 * {@code CacheInvalidationRequested} precedents): identifiers and stored
 * values only, published inside the command's transaction so the publication
 * registry entry commits atomically with the store write — a consumer failing
 * AFTER_COMMIT leaves the registry entry incomplete for the framework's retry
 * instead of losing the fact.
 *
 * <p>{@code username} carries the login-side principal name (the email
 * subject) because that is the key the session index is built on: Spring
 * Session's {@code PrincipalNameIndexResolver} indexes every saved session by
 * {@code authentication.getName()}, and {@code SpringSessionBackedSessionRegistry
 * #getAllSessions(Object, boolean)} looks sessions up by that same name. The
 * event is therefore self-contained for its consumer (the
 * {@code AccountStatusSessionInvalidator} in platform-infra) — no query back
 * into the identity module is needed to expire the account's sessions.
 *
 * <p>The event is published for BOTH directions (disable and enable) — the
 * domain fact stays complete for any future consumer (e.g. an enable
 * notification). The security consumer acts on {@code enabled == false} only:
 * activation expires nothing (an enabled account's sessions carry no security
 * liability), the CodeRabbit round-1 adoption on the fix plan itself
 * (commit {@code e27a94b} on {@code plan/comprehensive-review-ar-fix}).
 */
public record AccountStatusChanged(
        UUID userId,
        String username,
        boolean enabled
) {
}
