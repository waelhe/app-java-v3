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

    /**
     * The 2026-11-06 session-9 #811 final review (Major · Security &
     * Privacy): the generated diagnostic representation carried the RAW
     * one-time redemption right AND both addresses (PII) — any log line
     * or exception message that included the event printed them, and a
     * log reader could execute the email change before {@code expiresAt}.
     * The diagnostic shape keeps the identifier and the expiry; the
     * credential and the addresses are redacted (the payload-exception
     * convention governs the MAIL leg, never the logs).
     */
    @Override
    public String toString() {
        return "EmailChangeRequestedEvent[userId=" + userId + ", oldEmail=[REDACTED], "
                + "newEmail=[REDACTED], changeToken=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
