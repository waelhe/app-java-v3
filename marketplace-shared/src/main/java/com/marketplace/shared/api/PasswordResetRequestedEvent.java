package com.marketplace.shared.api;

/**
 * Event published when a user requests a password reset.
 * <p>Consumed by {@code NotificationEventListener} to send password reset email.
 */
public record PasswordResetRequestedEvent(String email, String resetToken) {
}
