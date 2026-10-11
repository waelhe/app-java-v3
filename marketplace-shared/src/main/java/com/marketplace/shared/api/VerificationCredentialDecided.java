package com.marketplace.shared.api;

import java.util.UUID;

/**
 * ADR-0001 (plan §Phase 1) — the domain fact that a verification credential
 * reached a decision ({@code APPROVED} / {@code REJECTED} / {@code REVOKED}).
 * Published by the identity module's decision command inside its own
 * transaction (the {@code UserRoleChanged} house pattern): identifiers and
 * stored names only, so shared-api stays free of identity-domain types.
 *
 * <p><b>The separation the event carries:</b> a credential decision is
 * EVIDENCE, never authority — the command that publishes this fact writes no
 * role row anywhere (the ADR-0001 rule: verification informs the human
 * administrator's grant decision; it never performs it automatically).
 * Consumers (trust surfaces, notifications) read the fact; none of them may
 * derive authority from it.
 *
 * <p>{@code credentialType}/{@code decision} carry the stored enum names
 * (the {@code PaymentStateChangedEvent} String-vocabulary precedent), so the
 * record shape evolves without coupling consumers to the identity module's
 * enum identity.
 */
public record VerificationCredentialDecided(
        UUID credentialId,
        UUID userId,
        String credentialType,
        String decision,
        String actor
) {
}
