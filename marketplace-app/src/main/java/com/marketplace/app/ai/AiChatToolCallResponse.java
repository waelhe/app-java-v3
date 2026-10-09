package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/** Structured tool invocation retained by Spring AI Session. */
@Schema(name = "AiChatToolCallResponse")
public record AiChatToolCallResponse(
        String id,
        String type,
        String name,
        String arguments) {
}
