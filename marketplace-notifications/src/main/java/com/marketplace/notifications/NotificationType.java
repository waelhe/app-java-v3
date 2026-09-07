package com.marketplace.notifications;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the notification types a
 * preference is expressed against. These are exactly the two types
 * {@code NotificationService} emits today from its two event points
 * (booking created / payment state changed) — the single source of truth
 * for the type strings, so a preference row can never drift from a
 * delivered notification type.
 */
public enum NotificationType {
    BOOKING_CREATED,
    PAYMENT_STATE
}
