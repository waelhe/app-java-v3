package com.marketplace.shared.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Summary DTO for provider listings — used by admin SPI.
 * Decouples admin from the full JPA entity in the catalog module.
 */
public record ProviderListingSummary(
        UUID id,
        String title,
        String category,
        BigDecimal price,
        UUID providerId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
