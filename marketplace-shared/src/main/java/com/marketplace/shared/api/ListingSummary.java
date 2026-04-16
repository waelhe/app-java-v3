package com.marketplace.shared.api;

import java.math.BigDecimal;
import java.util.UUID;

public record ListingSummary(
        UUID id,
        String title,
        String category,
        BigDecimal price,
        String providerName
) {
}
