package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * email-change journey's mail-leg event — published by identity when the
 * password-verified request issues the one-time redemption right, carried
 * across the module boundary to the notifications renderer (the
 * PasswordResetRequestedEvent payload-exception convention verbatim: the
 * one-time credential IS the mail's content, never a re-derivable
 * identifier). The notifications-side listener/template is this packet's
 * recorded follow-up — the event type, its fields and its TTL semantics
 * are frozen here so the renderer lands without re-negotiating the
 * contract.
 */
public record EmailChangeRequestedEvent(
        UUID userId,
        String oldEmail,
        String newEmail,
        String changeToken,
        Instant expiresAt
) {
}
