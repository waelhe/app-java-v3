package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Event published when a new user successfully registers.
 * <p>Consumed by {@code NotificationEventListener} to send welcome/verification email.
 */
public record UserRegisteredEvent(UUID userId, String email, String displayName, String verificationToken) {
}
