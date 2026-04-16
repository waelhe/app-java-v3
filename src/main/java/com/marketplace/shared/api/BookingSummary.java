package com.marketplace.shared.api;

import com.marketplace.booking.BookingStatus;

import java.time.Instant;
import java.util.UUID;

public record BookingSummary(
        UUID id,
        UUID consumerId,
        UUID providerId,
        UUID listingId,
        BookingStatus status,
        Long priceCents,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {
}
