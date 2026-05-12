package com.marketplace.shared.api;

import java.util.UUID;

public record BookingCreatedEvent(UUID bookingId, UUID consumerId, UUID providerId) {
    public BookingCreatedEvent(UUID bookingId) {
        this(bookingId, null, null);
    }
}
