package com.marketplace.reviews;

/**
 * W1 (yelp-level plan §4.5): the review's moderation state — the "soft
 * status column" the plan names, on the review row itself.
 *
 * <p><b>Vocabulary (the house's PostStatus twin):</b>
 * <ul>
 *   <li>{@link #PUBLISHED} — the public surface. Every pre-W1 row carries it
 *       (V72's DEFAULT backfills losslessly) and every booking-origin
 *       review is born with it — zero visible change for the existing
 *       data.</li>
 *   <li>{@link #PENDING_REVIEW} — the plan's first-three queue: an
 *       account's first organic reviews await an explicit moderation
 *       approval before any public read (list, aggregate, cached page)
 *       serves them.</li>
 *   <li>{@link #HIDDEN_BY_MODERATOR} — the moderated-away state: a
 *       rejected queue item or a resolved report's HIDE outcome. Terminal;
 *       the row survives (never a hard delete — the data-immutability
 *       rule the soft delete already enforces since V6).</li>
 * </ul>
 */
public enum ReviewModerationStatus {
    PUBLISHED,
    PENDING_REVIEW,
    HIDDEN_BY_MODERATOR
}
