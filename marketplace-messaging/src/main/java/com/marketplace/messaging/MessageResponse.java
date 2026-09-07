package com.marketplace.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * The REST contract for a single conversation message (B1):
 * {@code senderId} — the author's user id, mapped implicitly by MapStruct
 * from {@link Message#getSenderId()} — lets consuming clients tell who
 * wrote each message without a secondary user lookup.
 */
@org.springframework.modulith.NamedInterface("messaging-api")
public record MessageResponse(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String content,
        boolean read,
        Instant createdAt,
        Instant updatedAt
) {
}
