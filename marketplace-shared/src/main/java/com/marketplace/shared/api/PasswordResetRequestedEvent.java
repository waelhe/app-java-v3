package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A-04 (official-compliance plan §6, wave A — A.1 the password-reset
 * journey): published by identity when an account owner requests a password
 * reset, consumed by notifications for the {@code email/password-reset}
 * template (the dormant template activated — the plan's own A.2 wording —
 * its three model variables {@code name}, {@code resetLink} and
 * {@code expirationMinutes} are exactly this record's payload plus the
 * resolved display name).
 *
 * <p><b>The payload exception, documented (the house "identifiers only"
 * convention — SavedSearchMatchedEvent's own javadoc — deliberately does
 * NOT apply here):</b> this event carries the reset DEEP LINK, i.e. the
 * one-time credential itself, because the mail leg IS the delivery channel
 * for that credential and the Modulith event publication is the only
 * module-boundary crossing between the issuer (identity, whose
 * allowedDependencies carry shared-api/shared-security/shared-jpa only)
 * and the renderer (notifications, the house's only module wired to the
 * mail infrastructure). A credential that must be rendered cannot arrive
 * as an identifier — it is the mail's CONTENT, not a re-derivable business
 * fact.</p>
 *
 * <p><b>The lifecycle bound on that copy (measured against the official
 * Modulith events reference, fetched to
 * {@code scripts/official-docs-fetch/modulith-events.txt}):</b> the
 * registry's completion handling is the official lifecycle — with the
 * house {@code completion-mode: archive} the row is archived on completion
 * and purged by the standing {@code EventPublicationCleanup} job (the
 * official {@code CompletedEventPublications.deletePublicationsOlderThan}
 * API, daily 03:00 UTC, 7-day retention); in {@code dev} the house runs
 * {@code completion-mode: delete} and the row is deleted at completion.
 * The token's own TTL (default 30 minutes) bounds the link's usability
 * regardless — the archived copy outlives the credential it carries, never
 * the other way around. The token TABLE stores only the SHA-256 digest
 * (OWASP Forgot Password Cheat Sheet — the declared trusted community
 * source, plan §5.3).</p>
 *
 * <p>Publication semantics: inside the requester's own transaction (the
 * {@code @ApplicationModuleListener} unit on the consumer side) — the
 * registry entry commits atomically with the token row, and a failed mail
 * leg leaves the publication incomplete for the framework's retry (the
 * resubmission housekeeping already standing in platform-infra).</p>
 */
public record PasswordResetRequestedEvent(
        UUID userId,
        String displayName,
        String resetLink,
        Instant expiresAt
) {
}
