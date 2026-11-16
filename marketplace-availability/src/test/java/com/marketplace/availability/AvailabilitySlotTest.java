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

    /**
     * R2 (comprehensive-review-ar-fix plan §4/R2): the booked flag and the
     * owner are ONE claim — {@code markBooked(bookingId)} sets both, so a
     * live booked row always tells who holds the window.
     */
    @Test
    void markBookedClaimsSlotInTheBookingName() {
        UUID providerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        AvailabilitySlot slot = AvailabilitySlot.open(providerId,
                Instant.parse("2026-06-01T09:00:00Z"), Instant.parse("2026-06-01T10:00:00Z"));

        slot.markBooked(bookingId);

        assertThat(slot.isBooked()).isTrue();
        assertThat(slot.getHeldByBookingId()).isEqualTo(bookingId);
    }

    /** R2: the release clears the flag and the owner together — no orphaned hold. */
    @Test
    void markAvailableClearsOwnershipWithTheFlag() {
        UUID providerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        AvailabilitySlot slot = AvailabilitySlot.open(providerId,
                Instant.parse("2026-06-01T09:00:00Z"), Instant.parse("2026-06-01T10:00:00Z"));
        slot.markBooked(bookingId);

        slot.markAvailable();

        assertThat(slot.isBooked()).isFalse();
        assertThat(slot.getHeldByBookingId()).isNull();
    }

    /** R2: an open slot carries no ownership — nothing to hold, nobody named. */
    @Test
    void openSlotCarriesNoOwnership() {
        AvailabilitySlot slot = AvailabilitySlot.open(UUID.randomUUID(),
                Instant.parse("2026-06-01T09:00:00Z"), Instant.parse("2026-06-01T10:00:00Z"));

        assertThat(slot.getHeldByBookingId()).isNull();
    }
}
