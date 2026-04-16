package com.marketplace.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingServiceTest {

    private final PricingRuleRepository ruleRepository = mock(PricingRuleRepository.class);
    private final PricingService service = new PricingService(ruleRepository);

    @Test
    void calculatePrice_withCategoryRule() {
        PricingRule rule = PricingRule.create("Services", "services",
                new BigDecimal("15.0000"), new BigDecimal("5.0000"));
        when(ruleRepository.findByCategoryAndActiveTrue("services"))
                .thenReturn(Optional.of(rule));

        var breakdown = service.calculatePrice(10000L, "services");

        assertEquals(10000L, breakdown.basePriceCents());
        assertEquals(500L, breakdown.discountCents());
        assertEquals(9500L, breakdown.subtotalCents());
        assertEquals(1425L, breakdown.taxCents());
        assertEquals(10925L, breakdown.totalCents());
    }

    @Test
    void calculatePrice_fallbackToDefaultRule() {
        when(ruleRepository.findByCategoryAndActiveTrue("unknown")).thenReturn(Optional.empty());
        when(ruleRepository.findFirstByActiveTrueOrderByCreatedAtDesc()).thenReturn(Optional.empty());

        var breakdown = service.calculatePrice(10000L, "unknown");

        // Default: 15% tax, 0% discount
        assertEquals(10000L, breakdown.basePriceCents());
        assertEquals(0L, breakdown.discountCents());
        assertEquals(10000L, breakdown.subtotalCents());
        assertEquals(1500L, breakdown.taxCents());
        assertEquals(11500L, breakdown.totalCents());
    }

    @Test
    void calculatePrice_zeroDiscount() {
        PricingRule rule = PricingRule.create("No Discount", "test",
                new BigDecimal("10.0000"), BigDecimal.ZERO);
        when(ruleRepository.findByCategoryAndActiveTrue("test"))
                .thenReturn(Optional.of(rule));

        var breakdown = service.calculatePrice(5000L, "test");

        assertEquals(0L, breakdown.discountCents());
        assertEquals(5000L, breakdown.subtotalCents());
        assertEquals(500L, breakdown.taxCents());
        assertEquals(5500L, breakdown.totalCents());
    }
}