package com.marketplace.shared.api;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — the reviewer identity wave, G28/G29): port for
 * the reviewer-profile statistics the public reviewer page renders — the
 * plan's own field list "عدّاد مراجعات موثقة/عاملة، أوسمته" split by origin
 * plus the cumulative helpful-vote count the badges derive from (§5 W4:
 * "أوسمة المراجع (موثّق المعاملات، أصوات مفيد تراكمية)").
 *
 * <p>The {@code ReviewStatsPort} pattern (SYSTEM.md §6): the port lives
 * here in shared-api, the adapter in marketplace-reviews — the identity
 * module (the page's owner per the plan's §7 allocation) depends on
 * {@code shared :: shared-api} only, never on the reviews module's
 * internals, so the Modulith verification stays the unedited guard.
 *
 * <p><b>Badges are derived, never stored</b> (the plan's §5 W4 wording
 * reads them from the two measured facts; G29 closes as a read, not as a
 * table): this port returns the FACTS — the page's owner derives the labels
 * from them, so the thresholds live in exactly one presentation place and
 * the measurement semantics in exactly one data place.
 *
 * <p><b>Measurement semantics (the W1 seams this builds on):</b>
 * <ul>
 *   <li>{@code verifiedReviewCount} / {@code organicReviewCount} — the
 *       reviewer's PUBLISHED live reviews grouped by the {@code origin}
 *       column (V85's provenance), both directions — the same population
 *       {@code countPublishedByReviewerIds} counts for the per-review
 *       {@code reviewerReviewCount} block, so the page's two counters sum
 *       to the number the provider page's review rows already show;</li>
 *   <li>{@code helpfulVoteCount} — the cumulative "helpful" signal the
 *       reviewer's live reviews received (W1's {@code review_votes}); an
 *       unvoted vote is a soft-deleted row and therefore gone from the
 *       count, while a review later hidden by moderation keeps the votes
 *       it genuinely received — the badge measures the community's
 *       endorsement, not the row's current visibility.</li>
 * </ul>
 *
 * <p>The measurement is always recomputed from the tables (never a stored
 * aggregate) — the same recompute-is-truth rule {@code ReviewStatsPort}
 * documents for the provider averages.
 */
public interface ReviewerStatsPort {

    /**
     * The reviewer-profile facts for one user. An author with no published
     * reviews answers the all-zero instance (the page still renders — the
     * "not yet a reviewer" profile), never an exception: the page's own
     * 404 is the identity module's user-existence gate, not a reviews
     * fact.
     *
     * @param reviewerId the reviewer's user id (the {@code reviews.reviewer_id}
     *                   space, A1)
     */
    ReviewerStats findReviewerStats(UUID reviewerId);
}
