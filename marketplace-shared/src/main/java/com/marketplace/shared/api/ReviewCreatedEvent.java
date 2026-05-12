package com.marketplace.shared.api;

import java.util.UUID;

public record ReviewCreatedEvent(UUID reviewId, UUID reviewerId, UUID providerId) {
    public ReviewCreatedEvent(UUID reviewId) {
        this(reviewId, null, null);
    }
}
