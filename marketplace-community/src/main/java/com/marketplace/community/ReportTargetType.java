package com.marketplace.community;

/**
 * L45 (neighborhood community plan §5 — the moderation &amp; reports layer):
 * what a {@link ContentReport} targets. The plan's own words: "تعداد قابل
 * للامتداد" — the enumeration is closed TODAY (POST/COMMENT, the L42
 * authored-content surfaces) and the widening points are already named by
 * the plan itself: reports on messages and listings arrive with L44 or a
 * product decision, and each widening is a CHECK migration (the D-N7
 * discipline: the DB-side membership guard moves with the enum, the
 * V64→future sequence the RECOMMENDATION widening of L43 follows).
 *
 * <p>The {@code target_id} is the target's own id inside its owning
 * surface — a {@code neighborhood_posts.id} for POST, a
 * {@code post_comments.id} for COMMENT, a {@code reviews.id} for REVIEW.
 * Resolution of the id to a row, and the honest 404 for an
 * unknown/hidden/deleted target, live in the service's target gate (the
 * L42 {@code visiblePost} convention; REVIEW resolves through the
 * reviews module's shared-api {@code ReviewLookupPort} — the module law:
 * this module's allowed dependencies are shared-only).
 *
 * <p><b>W1 (yelp-level plan §4.5):</b> {@link #REVIEW} is the L45
 * widening the original javadoc reserved ("each widening is a CHECK
 * migration") — the V73+V74 pair widens the DB guard in the V68/V69
 * locking shape, and the service's two switches gate the new target
 * exactly like the existing pair (visibility resolve + moderation hide).
 */
public enum ReportTargetType {
    POST,
    COMMENT,
    REVIEW
}
