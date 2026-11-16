package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** JSON payload for the documented SSE conversation/token/done/failure events. */
@Schema(name = "AiChatStreamEvent")
public record AiChatStreamEvent(
        String type,
        UUID conversationId,
        String text,
        String message) {
}
