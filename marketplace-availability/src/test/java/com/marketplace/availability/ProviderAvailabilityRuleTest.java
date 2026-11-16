package com.marketplace.availability;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderAvailabilityRuleTest {

    @Test
    void createReturnsRuleWithGivenValues() {
        UUID providerId = UUID.randomUUID();
        DayOfWeek dayOfWeek = DayOfWeek.MONDAY;
        LocalTime startTime = LocalTime.of(9, 0);
        LocalTime endTime = LocalTime.of(17, 0);

        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId, dayOfWeek, startTime, endTime);

        assertThat(rule.getId()).isNotNull();
    }
}
