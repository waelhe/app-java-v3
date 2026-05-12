package com.marketplace.availability;

import java.time.Instant;
import java.util.UUID;

public record AvailabilitySlot(UUID providerId, UUID listingId, Instant startsAt, Instant endsAt, boolean available) {
}
