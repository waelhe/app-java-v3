package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

public record BookingInfo(
        UUID providerId,
        UUID consumerId,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
