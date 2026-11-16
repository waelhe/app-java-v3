package com.marketplace.shared.api;

import java.util.UUID;

public record BookingConfirmedEvent(UUID bookingId) {
}
