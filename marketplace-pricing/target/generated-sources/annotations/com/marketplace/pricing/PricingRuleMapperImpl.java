package com.marketplace.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:35:23+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class PricingRuleMapperImpl implements PricingRuleMapper {

    @Override
    public PricingRuleResponse toResponse(PricingRule rule) {
        if ( rule == null ) {
            return null;
        }

        UUID id = null;
        String name = null;
        String category = null;
        BigDecimal taxRate = null;
        BigDecimal discountPct = null;
        boolean active = false;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = rule.getId();
        name = rule.getName();
        category = rule.getCategory();
        taxRate = rule.getTaxRate();
        discountPct = rule.getDiscountPct();
        active = rule.isActive();
        createdAt = rule.getCreatedAt();
        updatedAt = rule.getUpdatedAt();

        PricingRuleResponse pricingRuleResponse = new PricingRuleResponse( id, name, category, taxRate, discountPct, active, createdAt, updatedAt );

        return pricingRuleResponse;
    }
}
