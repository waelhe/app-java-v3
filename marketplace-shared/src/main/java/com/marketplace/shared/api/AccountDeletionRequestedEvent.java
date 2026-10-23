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
}
