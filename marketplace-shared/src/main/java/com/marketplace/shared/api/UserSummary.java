package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        String role,
        Instant createdAt,
        Instant updatedAt
) {
}
