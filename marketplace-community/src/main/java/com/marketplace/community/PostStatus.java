package com.marketplace.community;

/**
 * L42 (neighborhood community plan §5): a neighborhood post's moderation
 * status. The column exists from day one so the moderation layer (L45)
 * arrives as a state flip, not a schema change — but <b>no code path in
 * this layer writes anything but {@code VISIBLE}</b>: hiding without the
 * moderation surface is structurally impossible ("إخفاء بدون إشراف
 * مستحيل" — the plan's own words). The DB CHECK pins the SQL side
 * (D-N7); only L45's resolve command flips a stored row to
 * {@code HIDDEN_BY_MODERATOR}.
 *
 * <p>The read side is the honest consumer: the feed and the comment reads
 * answer {@code VISIBLE} only (a hidden post is absent — the same honest
 * 404 as an unknown id), which is what the plan's criterion 5 pins.
 */
public enum PostStatus {
    VISIBLE,
    HIDDEN_BY_MODERATOR
}
