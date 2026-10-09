package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Stable HTTP response contract for one AI-chat turn.
 *
 * @param conversationId server-owned conversation handle to reuse on later turns
 * @param answer         assistant's textual answer
 */
@Schema(name = "AiChatResponse", description = "Assistant answer and the conversation ID for the next turn")
public record AiChatResponse(
        @Schema(description = "Conversation ID to reuse for subsequent messages")
        UUID conversationId,

        @Schema(description = "Assistant's textual answer")
        String answer) {
}
