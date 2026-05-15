package com.marketplace.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ListingResponse(
        UUID id,
        String title,
        String description,
        String category,
        BigDecimal price,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
}
