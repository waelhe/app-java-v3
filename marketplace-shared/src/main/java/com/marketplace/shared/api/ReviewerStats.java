package com.marketplace.shared.api;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — the reviewer identity wave, G28/G29): the
 * reviewer-profile statistics a public reviewer page renders — the
 * origin-split published-review counters and the cumulative helpful-vote
 * count. The reviews module owns the measurement (the {@code reviews} /
 * {@code review_votes} tables); the identity module — the page's owner, the
 * plan's §7 allocation "صفحة المراجع من marketplace-identity" — reads it
 * through this seam exactly the way the provider public page reads its
 * reviews block through {@link PublishedReviewsPort}.
 *
 * <p>The {@code ReviewStatsPort} pattern (SYSTEM.md §6): port here in
 * shared-api, adapter in marketplace-reviews — the page's module depends on
 * {@code shared :: shared-api} only, never on the reviews module's
 * internals, so the Modulith verification stays the unedited guard.
 *
 * <p><b>Id space (A1, measured):</b> {@code reviewerId} is the reviews'
 * {@code reviewer_id} — the {@code users.id} space every cross-module id
 * column carries. The caller (the identity page, keyed by the same user id)
 * passes it directly; no profile resolution anywhere.
 */
public record ReviewerStats(
        long verifiedReviewCount,
        long organicReviewCount,
        long helpfulVoteCount
) {
}
