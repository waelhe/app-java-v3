package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

/** Structured tool result retained by Spring AI Session. */
@Schema(name = "AiChatToolResponse")
public record AiChatToolResponse(
        String id,
        String name,
        String responseData) {
}
