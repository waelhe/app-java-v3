package com.marketplace.notifications;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the notification types a
 * preference is expressed against. These are exactly the types
 * {@code NotificationService} emits today from its event points
 * (booking created / payment state changed / lead received) — the single
 * source of truth for the type strings, so a preference row can never
 * drift from a delivered notification type.
 *
 * <p>L34 (realestate systems plan §5 — lead capture): {@code LEAD_RECEIVED}
 * joins as the third type — a point addition on the standing pattern
 * ("القناة الجديدة إضافة نقطة واحدة"): the enum is the single source of
 * truth, the per-type/channel preference machinery (L22) governs its
 * delivery from day one with no new mechanism.
 *
 * <p>L35 (realestate systems plan §5 — saved searches and alerts):
 * {@code SAVED_SEARCH_MATCH} joins as the fourth type — the same point
 * addition (the V55 CHECK widens the DB-side membership guard to match).
 *
 * <p>L42 (neighborhood community plan §5 — the posts/feed/comments layer):
 * {@code POST_COMMENTED} joins as the fifth type — the same point addition
 * ("نقطة إضافة واحدة" D-N12; the V62 CHECK widens the DB-side membership
 * guard to match). The recipient is the post's author; the self-comment
 * skip is the listener's own policy, not this enum's concern.
 */
public enum NotificationType {
    BOOKING_CREATED,
    PAYMENT_STATE,
    LEAD_RECEIVED,
    SAVED_SEARCH_MATCH,
    POST_COMMENTED
}
