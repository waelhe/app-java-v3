package com.marketplace.messaging;

import java.time.Instant;
import java.util.UUID;

public record ConversationResponse(
        UUID id,
        UUID bookingId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.getId(),
                conversation.getBookingId(),
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }
}
