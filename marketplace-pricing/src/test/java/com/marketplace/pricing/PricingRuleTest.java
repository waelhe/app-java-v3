package com.marketplace.pricing;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Select.field;

class PricingRuleTest {

    @Test
    void shouldCreatePricingRule() {
        PricingRule rule = Instancio.of(PricingRule.class)
                .set(field(PricingRule::getName), "Standard")
                .set(field(PricingRule::getCategory), "general")
                .set(field(PricingRule::getTaxRate), new BigDecimal("0.15"))
                .set(field(PricingRule::getDiscountPct), new BigDecimal("0.10"))
                .set(field(PricingRule::isActive), true)
                .create();

        assertThat(rule.getName()).isEqualTo("Standard");
        assertThat(rule.getCategory()).isEqualTo("general");
        assertThat(rule.getTaxRate()).isEqualByComparingTo("0.15");
        assertThat(rule.getDiscountPct()).isEqualByComparingTo("0.10");
        assertThat(rule.isActive()).isTrue();
        assertThat(rule.getId()).isNotNull();
    }

    @Test
    void shouldDeactivate() {
        PricingRule rule = Instancio.of(PricingRule.class)
                .set(field(PricingRule::getName), "Test")
                .set(field(PricingRule::getCategory), (String) null)
                .set(field(PricingRule::getTaxRate), BigDecimal.ZERO)
                .set(field(PricingRule::getDiscountPct), BigDecimal.ZERO)
                .set(field(PricingRule::isActive), true)
                .create();
        rule.deactivate();

        assertThat(rule.isActive()).isFalse();
    }

    @Test
    void shouldActivate() {
        PricingRule rule = Instancio.of(PricingRule.class)
                .set(field(PricingRule::getName), "Test")
                .set(field(PricingRule::getCategory), (String) null)
                .set(field(PricingRule::getTaxRate), BigDecimal.ZERO)
                .set(field(PricingRule::getDiscountPct), BigDecimal.ZERO)
                .set(field(PricingRule::isActive), true)
                .create();
        rule.deactivate();
        rule.activate();

        assertThat(rule.isActive()).isTrue();
    }

    @Test
    void shouldAllowNullCategory() {
        PricingRule rule = Instancio.of(PricingRule.class)
                .set(field(PricingRule::getName), "Global")
                .set(field(PricingRule::getCategory), (String) null)
                .set(field(PricingRule::getTaxRate), new BigDecimal("0.10"))
                .set(field(PricingRule::getDiscountPct), BigDecimal.ZERO)
                .set(field(PricingRule::isActive), true)
                .create();

        assertThat(rule.getCategory()).isNull();
    }
}
