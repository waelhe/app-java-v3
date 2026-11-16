package com.marketplace.availability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderTimeOffTest {

    @Test
    void createReturnsTimeOffWithGivenValues() {
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-07-01T00:00:00Z");
        Instant endsAt = Instant.parse("2026-07-07T00:00:00Z");

        ProviderTimeOff timeOff = ProviderTimeOff.create(providerId, startsAt, endsAt);

        assertThat(timeOff.getId()).isNotNull();
    }
}
