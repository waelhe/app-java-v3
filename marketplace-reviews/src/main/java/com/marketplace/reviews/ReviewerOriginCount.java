package com.marketplace.reviews;

/**
 * W4 (yelp-level plan §5 — G28/G29): the grouped per-origin published-review
 * count for one reviewer (the reviewer-profile page's split counters — the
 * {@code ReviewerReviewCount} batch form's single-reviewer twin).
 */
public record ReviewerOriginCount(String origin, long count) {
}
