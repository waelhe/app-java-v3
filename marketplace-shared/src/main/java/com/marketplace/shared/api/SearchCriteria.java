package com.marketplace.shared.api;

import java.time.Instant;

public record SearchCriteria(
        String query,
        String category,
        Long minPriceCents,
        Long maxPriceCents,
        Integer minRating,
        String location,
        Instant availableFrom,
        Instant availableTo,
        String sort
) {
}
