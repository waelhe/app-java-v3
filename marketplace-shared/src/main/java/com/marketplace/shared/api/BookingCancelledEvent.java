package com.marketplace.shared.api;

import java.util.UUID;

public record BookingCancelledEvent(UUID bookingId) {
}
