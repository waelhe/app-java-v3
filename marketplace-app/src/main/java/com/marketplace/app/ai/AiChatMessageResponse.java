package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** A persisted Spring AI Session event exposed as one chat-history item. */
@Schema(name = "AiChatMessageResponse")
public record AiChatMessageResponse(
        String id,
        Instant timestamp,
        String role,
        String content,
        boolean archived,
        boolean hasToolCalls) {
}
