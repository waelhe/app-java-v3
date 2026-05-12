package com.marketplace.booking;

import java.time.Instant;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID listingId,
        String status,
        Instant startsAt,
        Instant endsAt,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getListingId(),
                booking.getStatus().name(),
                booking.getStartsAt(),
                booking.getEndsAt(),
                booking.getNotes(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }
}
