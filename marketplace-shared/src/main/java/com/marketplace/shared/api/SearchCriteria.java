package com.marketplace.shared.api;

import java.math.BigDecimal;

public record SearchCriteria(
        String query,
        String category,
        BigDecimal minPrice,
        BigDecimal maxPrice
) {
}
