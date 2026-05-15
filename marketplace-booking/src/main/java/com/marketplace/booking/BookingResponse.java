package com.marketplace.booking;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID listingId,
        String status,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}
