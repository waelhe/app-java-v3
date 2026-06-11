package com.marketplace.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PricingRuleResponse(
        UUID id,
        String name,
        String category,
        BigDecimal taxRate,
        BigDecimal discountPct,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
