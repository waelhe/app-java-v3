package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** A Spring AI Session event represented by the application's stable JSON contract. */
@Schema(name = "AiChatMessageResponse")
public record AiChatMessageResponse(
        String id,
        Instant timestamp,
        String role,
        String content,
        boolean archived,
        boolean hasToolCalls,
        List<AiChatToolCallResponse> toolCalls,
        List<AiChatToolResponse> toolResponses) {
}
