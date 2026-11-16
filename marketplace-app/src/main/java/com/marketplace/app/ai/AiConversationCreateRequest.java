package com.marketplace.app.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/** Optional initial metadata for an AI conversation created before the first message. */
@Schema(name = "AiConversationCreateRequest")
public record AiConversationCreateRequest(
        @Size(max = 120)
        @Schema(description = "Optional user-visible conversation title", maxLength = 120)
        String title) {
}
