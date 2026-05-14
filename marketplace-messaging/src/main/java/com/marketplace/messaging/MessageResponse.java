package com.marketplace.messaging;

import java.time.Instant;
import java.util.UUID;

public record MessageResponse(
        UUID id,
        UUID conversationId,
        String content,
        boolean read,
        Instant createdAt,
        Instant updatedAt
) {
    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getConversationId(),
                message.getContent(),
                message.isRead(),
                message.getCreatedAt(),
                message.getUpdatedAt()
        );
    }
}
