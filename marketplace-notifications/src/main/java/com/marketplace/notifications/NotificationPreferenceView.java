package com.marketplace.notifications;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the effective state of one
 * user×type×channel switch as the preferences endpoints return it — the
 * stored override when one exists, otherwise the enabled default.
 *
 * @param type     the notification type the switch governs
 * @param channel  the delivery channel the switch governs
 * @param enabled  whether that channel stays on for that type
 */
public record NotificationPreferenceView(NotificationType type,
                                         NotificationChannel channel,
                                         boolean enabled) {
}
