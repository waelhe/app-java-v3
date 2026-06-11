package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PricingRuleMapperTest {

    private final PricingRuleMapper mapper = Mappers.getMapper(PricingRuleMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        PricingRule rule = new PricingRule(id, "Test Rule", "services",
                new BigDecimal("0.1500"), new BigDecimal("0.0500"));

        PricingRuleResponse response = mapper.toResponse(rule);

        assertEquals(id, response.id());
        assertEquals("Test Rule", response.name());
        assertEquals("services", response.category());
        assertEquals(0, new BigDecimal("0.1500").compareTo(response.taxRate()));
        assertEquals(0, new BigDecimal("0.0500").compareTo(response.discountPct()));
        assertTrue(response.active());
    }
}
