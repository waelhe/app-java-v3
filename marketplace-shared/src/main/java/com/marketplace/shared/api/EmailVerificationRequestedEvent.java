package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A-04 (official-compliance plan §6, wave A — A.2 email verification): the
 * dormant {@code email/welcome} template's activation. Published by
 * identity at BOTH issuance sites — self-service registration (the account
 * is born held: {@code enabled=false}, the documented debt the register
 * javadoc carried, closed by this unit) and the anonymous resend surface
 * for an account still awaiting verification — consumed by notifications
 * for the verification-carrying welcome mail (the template's model
 * variables {@code name} and {@code verificationLink}).
 *
 * <p><b>The payload exception, documented:</b> same ruling as
 * {@link PasswordResetRequestedEvent} — the one-time verification
 * credential is the mail's CONTENT (a deep link the renderer must emit
 * verbatim), not a re-derivable business identifier, so it crosses the
 * module boundary in the event payload; the registry lifecycle bound and
 * the hash-at-rest token table are measured there and apply identically
 * here.</p>
 *
 * <p><b>The state rule this event participates in (the ban-vs-verification
 * invariant, registered in the contracts ledger):</b> the account's
 * verification state is its LATEST EMAIL_VERIFICATION token row — a row
 * still unconsumed (expired or not) means PENDING (this event is being
 * published for it), a consumed row means the account either completed
 * verification or was invalidated by an administrative surface
 * (disable/pseudonymize consume outstanding tokens in their own
 * transaction — the FK-safe, Envers-auditable order). A consumed latest
 * row therefore refuses both resend and redemption: an administratively
 * disabled account can never be re-enabled through the verification
 * surface, and a verified one never asks again.</p>
 *
 * <p>Publication semantics: inside the issuing transaction — the registry
 * entry commits atomically with the token row; a failed mail leg stays
 * incomplete for the framework's retry.</p>
 */
public record EmailVerificationRequestedEvent(
        UUID userId,
        String displayName,
        String verificationLink,
        Instant expiresAt
) {
}
