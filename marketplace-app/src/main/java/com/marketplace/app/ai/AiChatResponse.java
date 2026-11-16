package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** Complete synchronous JSON response for one AI-chat turn. */
@Schema(name = "AiChatResponse", description = "Assistant answer and the conversation ID for the next turn")
public record AiChatResponse(
        @Schema(description = "Conversation ID to reuse for subsequent messages")
        UUID conversationId,

        @Schema(description = "Assistant's textual answer")
        String answer) {
}
