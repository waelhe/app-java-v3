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
        String currency,
        UUID providerId,
        String status,
        Integer maxGuests,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        String pausedReason
) {
    /** The pre-L33 eleven-component form — every existing construction site. */
    public ProviderListingView(UUID id, String title, String description, String category,
                               Long priceCents, String currency, UUID providerId, String status,
                               Integer maxGuests, Instant createdAt, Instant updatedAt) {
        this(id, title, description, category, priceCents, currency, providerId, status,
                maxGuests, createdAt, updatedAt, null, null);
    }
}
