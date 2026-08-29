package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only view of a provider listing for cross-module consumers
 * (for example the GraphQL presentation adapter).
 * Decouples SPI consumers from the JPA entity in the catalog module,
 * following the same DTO boundary used by {@link ProviderListingSummary}.
 */
public record ProviderListingView(
        UUID id,
        String title,
        String description,
        String category,
        Long priceCents,
        UUID providerId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}