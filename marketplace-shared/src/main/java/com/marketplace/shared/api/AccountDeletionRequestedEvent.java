package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * account self-deletion journey's mail-leg event — published by identity
 * when the authenticated request issues the one-time erasure right (the
 * same payload-exception convention as PasswordResetRequestedEvent: the
 * one-time credential is the mail's content). The notifications-side
 * listener/template is this packet's recorded follow-up.
 */
public record AccountDeletionRequestedEvent(
        UUID userId,
        String email,
        String deletionToken,
        Instant expiresAt
) {

    /**
     * The 2026-11-06 session-9 #811 final review (Major · Security &
     * Privacy): the record's generated diagnostic representation carried
     * the RAW one-time erasure right — any log line or exception message
     * that included the event printed the token, and a log reader could
     * execute the account deletion before {@code expiresAt}. The
     * diagnostic shape keeps the identifiers and the expiry; the
     * credential itself is redacted (the payload-exception convention
     * governs the MAIL leg, never the logs).
     */
    @Override
    public String toString() {
        return "AccountDeletionRequestedEvent[userId=" + userId + ", email=[REDACTED], "
                + "deletionToken=[REDACTED], expiresAt=" + expiresAt + "]";
    }
}
