package com.marketplace.messaging;

import java.time.Instant;
import java.util.UUID;

@org.springframework.modulith.NamedInterface("messaging-api")
public record MessageResponse(
        UUID id,
        UUID conversationId,
        String content,
        boolean read,
        Instant createdAt,
        Instant updatedAt
) {
}
