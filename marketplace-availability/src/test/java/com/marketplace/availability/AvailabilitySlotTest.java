package com.marketplace.availability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilitySlotTest {

    @Test
    void openCreatesUnbookedSlot() {
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");

        AvailabilitySlot slot = AvailabilitySlot.open(providerId, startsAt, endsAt);

        assertThat(slot.getId()).isNotNull();
        assertThat(slot.getProviderId()).isEqualTo(providerId);
        assertThat(slot.getStartsAt()).isEqualTo(startsAt);
        assertThat(slot.getEndsAt()).isEqualTo(endsAt);
        assertThat(slot.isBooked()).isFalse();
    }

    @Test
    void openGeneratesUniqueIds() {
        UUID providerId = UUID.randomUUID();
        Instant startsAt = Instant.parse("2026-06-01T09:00:00Z");
        Instant endsAt = Instant.parse("2026-06-01T10:00:00Z");

        AvailabilitySlot slot1 = AvailabilitySlot.open(providerId, startsAt, endsAt);
        AvailabilitySlot slot2 = AvailabilitySlot.open(providerId, startsAt, endsAt);

        assertThat(slot1.getId()).isNotEqualTo(slot2.getId());
    }
}
