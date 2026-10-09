package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** Conversation metadata from the Spring AI Session repository. */
@Schema(name = "AiConversationResponse")
public record AiConversationResponse(
        UUID conversationId,
        String title,
        Instant createdAt,
        Instant expiresAt) {
}
