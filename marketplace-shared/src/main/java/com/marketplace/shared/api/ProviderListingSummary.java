package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary DTO for provider listings — used by cross-module consumers
 * (e.g., admin) to avoid exposing the JPA entity directly.
 * Follows the same pattern as BookingSummary, UserSummary, PaymentSummary.
 */
public record ProviderListingSummary(
        UUID id,
        UUID providerId,
        String title,
        String description,
        String category,
        Long priceCents,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
