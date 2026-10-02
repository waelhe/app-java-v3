package com.marketplace.identity;

import com.marketplace.shared.api.ReviewerStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W4 (yelp-level plan §5 — G29): the badge derivation over the two measured
 * facts — the plan's two families («موثّق المعاملات، أصوات مفيد تراكمية»),
 * derived on every read, never stored.
 */
class ReviewerBadgeTest {

    @Test
    void derive_verifiedReviewerNeedsOnePublishedVerifiedReview() {
        assertEquals(List.of(ReviewerBadge.VERIFIED_REVIEWER),
                ReviewerBadge.derive(new ReviewerStats(1L, 0L, 0L)));
    }

    @Test
    void derive_helpfulReviewerNeedsTheCumulativeThreshold() {
        assertEquals(List.of(ReviewerBadge.HELPFUL_REVIEWER),
                ReviewerBadge.derive(new ReviewerStats(0L, 5L, ReviewerBadge.HELPFUL_VOTE_THRESHOLD)));
        // one vote short — the badge honestly stays off
        assertEquals(List.of(),
                ReviewerBadge.derive(new ReviewerStats(0L, 5L, ReviewerBadge.HELPFUL_VOTE_THRESHOLD - 1)));
    }

    @Test
    void derive_bothFamiliesCanHoldTogetherInDeclarationOrder() {
        assertEquals(List.of(ReviewerBadge.VERIFIED_REVIEWER, ReviewerBadge.HELPFUL_REVIEWER),
                ReviewerBadge.derive(new ReviewerStats(3L, 12L, 40L)));
    }

    @Test
    void derive_aNeverReviewerEarnsNothing() {
        assertEquals(List.of(), ReviewerBadge.derive(new ReviewerStats(0L, 0L, 0L)));
    }

    @Test
    void derive_organicOnlyReviewsNeverEarnTheVerifiedBadge() {
        // The origin split doing its job: general reviews alone are not
        // transaction-verified credibility.
        assertEquals(List.of(),
                ReviewerBadge.derive(new ReviewerStats(0L, 50L, 0L)));
    }

    @Test
    void derive_nullStatsIsTheEmptyListNotAnException() {
        assertEquals(List.of(), ReviewerBadge.derive(null));
    }
}
