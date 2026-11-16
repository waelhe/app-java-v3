package com.marketplace.shared.api;

import java.util.UUID;

/**
 * S2/N4/N6 root fix (comprehensive repair plan §10/1.5): published by the
 * identity module's role-change command inside its own transaction, the
 * domain fact that an account's role changed on BOTH stores of truth —
 * {@code users.role} (the domain record) and {@code auth_authorities}
 * (the login-side projection every future token's {@code roles} claim is
 * minted from, SecurityConfig {@code jwtTokenCustomizer}).
 *
 * <p>The Modulith house pattern ({@code ContentModeratedEvent} /
 * {@code PostCommentedEvent} precedents): identifiers and stored names
 * only, published inside the command's transaction so the publication
 * registry entry commits atomically with the two-store write — a consumer
 * failing AFTER_COMMIT leaves the registry entry incomplete for the
 * framework's retry instead of losing the fact.
 *
 * <p>{@code previousRole}/{@code newRole} carry the stored enum names
 * ({@code CONSUMER}/{@code PROVIDER}/{@code ADMIN} — the
 * {@code PaymentStateChangedEvent} String-vocabulary precedent: the event
 * carries the stored name, not the owning module's enum, so shared-api
 * stays free of identity-domain types).
 *
 * <p>R8 (comprehensive-review-ar fix plan §4, Wave 1): {@code username}
 * joins the payload — the login-side principal name the session index is
 * built on — so the {@code AccountStatusSessionInvalidator} consumer
 * (platform-infra) expires the account's sessions without querying back
 * into the identity module: a role change leaves the authorities inside
 * every live session stale, so all of them are expired and the next
 * authorization request re-authenticates against the new projection.
 * The record shape evolves with zero breakage: the registry tracks
 * (event, listener) pairs and no listener existed before this wave, so
 * no serialized publication row carries the old shape.
 */
public record UserRoleChanged(
        UUID userId,
        String username,
        String previousRole,
        String newRole
) {
}
