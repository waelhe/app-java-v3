package com.marketplace.notifications;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the notification delivery
 * channels a preference is expressed against.
 *
 * <p>Channel semantics (the roadmap's L22 scope): {@code EMAIL} is gated by
 * the stored preference before every send; {@code WS} is sent by default
 * and honors an explicit opt-out; {@code DB} — the in-app notification —
 * is always created (the roadmap: "inside the app always"), so a DB
 * <em>opt-out is rejected</em> at the write path (400) and the
 * {@code notification_preferences} schema CHECKs it can never be stored —
 * the API never reports a state the delivery path does not honor. A DB
 * opt-in ({@code enabled = true}) is accepted: it merely affirms the
 * default and keeps the channel set stable for future channels (push/SMS,
 * roadmap §7) to become single addition points.
 */
public enum NotificationChannel {
    DB,
    EMAIL,
    WS
}
