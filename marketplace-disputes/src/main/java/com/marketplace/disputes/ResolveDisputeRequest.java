package com.marketplace.disputes;

import jakarta.validation.constraints.NotNull;

/**
 * L24 (feature-expansion roadmap §5): the admin's resolve request — the
 * decision that carries the financial outcome. House {@code @RequestBody}
 * record pattern ({@code ReplyRequest}, L21).
 */
public record ResolveDisputeRequest(
        @NotNull DisputeResolution resolution
) {
}
