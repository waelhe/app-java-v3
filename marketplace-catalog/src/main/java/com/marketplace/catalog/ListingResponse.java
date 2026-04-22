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
    public static ListingResponse from(ProviderListing listing) {
        return new ListingResponse(
                listing.getId(),
                listing.getTitle(),
                listing.getDescription(),
                listing.getCategory(),
                BigDecimal.valueOf(listing.getPriceCents(), 2),
                listing.getCurrency(),
                listing.getCreatedAt(),
                listing.getUpdatedAt()
        );
    }
}
