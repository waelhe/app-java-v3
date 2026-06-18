package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Event published when a user verifies their email address.
 */
public record UserVerifiedEvent(UUID userId, String email) {
}
