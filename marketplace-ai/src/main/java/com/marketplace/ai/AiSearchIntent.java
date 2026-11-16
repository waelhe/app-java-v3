package com.marketplace.ai;

import java.math.BigDecimal;

public record AiSearchIntent(
        Intent intent,
        String query,
        String category,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Integer guests
) {
    public enum Intent {
        SEARCH,
        LISTING_DETAILS,
        GENERAL
    }
}
