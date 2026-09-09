package com.marketplace.reviews;

import java.time.Instant;
import java.util.UUID;

public record ReviewResponse(
        UUID id,
        UUID bookingId,
        Integer rating,
        String comment,
        String reply,
        String direction,
        Instant repliedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
