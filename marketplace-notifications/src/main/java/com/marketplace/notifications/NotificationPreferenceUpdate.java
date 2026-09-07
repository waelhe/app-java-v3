package com.marketplace.notifications;

import jakarta.validation.constraints.NotNull;

/**
 * L22 (feature-expansion roadmap §5, Week 2): one entry of the preferences
 * PUT body — a single type×channel switch to set. House {@code @RequestBody}
 * record pattern ({@code ResolveDisputeRequest}, L24).
 *
 * @param type     the notification type the switch governs
 * @param channel  the delivery channel the switch governs
 * @param enabled  the new channel state for that type
 */
public record NotificationPreferenceUpdate(
        @NotNull NotificationType type,
        @NotNull NotificationChannel channel,
        boolean enabled) {
}
