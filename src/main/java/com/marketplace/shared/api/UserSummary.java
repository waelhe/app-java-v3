package com.marketplace.shared.api;

import com.marketplace.identity.UserRole;

import java.time.Instant;
import java.util.UUID;

public record UserSummary(
        UUID id,
        String email,
        String displayName,
        UserRole role,
        Instant createdAt,
        Instant updatedAt
) {
}
