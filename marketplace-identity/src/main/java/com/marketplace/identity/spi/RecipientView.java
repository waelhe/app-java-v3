package com.marketplace.identity.spi;

import java.util.UUID;

/**
 * Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
 * staff notification recipient projection (oneof: user reference or bare
 * email).
 */
public record RecipientView(UUID id, UUID userId, String email, boolean active) {
}
