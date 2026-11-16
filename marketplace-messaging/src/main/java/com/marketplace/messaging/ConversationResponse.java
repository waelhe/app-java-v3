package com.marketplace.messaging;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID bookingId,
        Instant createdAt,
        Instant updatedAt
) {
}
