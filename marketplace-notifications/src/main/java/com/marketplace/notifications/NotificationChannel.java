package com.marketplace.notifications;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the notification delivery
 * channels a preference is expressed against.
 *
 * <p>Channel semantics (the roadmap's L22 scope): {@code EMAIL} is gated by
 * the stored preference before every send; {@code WS} is sent by default
 * and honors an explicit opt-out; {@code DB} — the in-app notification —
 * is always created (the roadmap: "inside the app always"). The {@code DB}
 * preference is still stored so a future channel (push/SMS, roadmap §7)
 * becomes a single addition point, exactly like the existing three.
 */
public enum NotificationChannel {
    DB,
    EMAIL,
    WS
}
