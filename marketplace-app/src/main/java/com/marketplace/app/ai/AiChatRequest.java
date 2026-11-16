package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Stable HTTP request contract for one authenticated AI-chat turn.
 *
 * @param message the user's non-blank message (maximum 4,000 characters)
 * @param conversationId existing caller-owned conversation to continue; omit
 *                       to create a conversation and use its returned ID
 */
@Schema(name = "AiChatRequest", description = "One authenticated user's message to the AI assistant")
public record AiChatRequest(
        @NotBlank
        @Size(max = 4000)
        @Schema(description = "User message", example = "Find family-friendly places near me", maxLength = 4000)
        String message,

        @Schema(description = "Existing conversation ID to continue; omit to create a new conversation")
        UUID conversationId) {
}
