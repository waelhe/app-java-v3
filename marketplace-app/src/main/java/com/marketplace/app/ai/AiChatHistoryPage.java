package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** One chronological page of a session's persisted event history. */
@Schema(name = "AiChatHistoryPage")
public record AiChatHistoryPage(
        UUID conversationId,
        int page,
        int size,
        List<AiChatMessageResponse> messages,
        boolean hasMore) {
}
