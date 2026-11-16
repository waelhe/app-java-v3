package com.marketplace.identity;

import com.marketplace.shared.api.ReviewerStats;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * W5 (yelp-level plan §5 — G26, «درجة ثقة للمراجع»): the derivation's
 * own arithmetic — the plan's three feeds («حساب معاملات، تاريخ، أصوات
 * مفيد») composed with the caps, the tier floors, and the plan's own
 * contrast: «مراجع موثوق ... مقابل حساب ولد اليوم».
 */
class ReviewerTrustScoreTest {

    @Test
    void dayOldAccountWithNoActivityIsTheHonestZeroNewcomer() {
        // The plan's own words: a day-old account with no activity carries no
        // trust — the score says so numerically, the tier reads it.
        ReviewerTrustScore score = ReviewerTrustScore.derive(
                new ReviewerStats(0L, 0L, 0L), Duration.ofDays(1));
        assertEquals(0, score.score());
        assertEquals(ReviewerTrustScore.TrustTier.NEWCOMER, score.tier());
    }

    @Test
    void nullStatsKeepsTheHistoryAlone_andNullEverythingIsTheHonestZero() {
        // Null stats (the not-yet-a-reviewer profile): no transaction and no
        // helpfulness facts — but the account's AGE is still a measured
        // fact, so it still counts (capped at its component's ceiling).
        ReviewerTrustScore aged = ReviewerTrustScore.derive(null, Duration.ofDays(400));
        assertEquals(20, aged.score());
        assertEquals(ReviewerTrustScore.TrustTier.NEWCOMER, aged.tier());
        // No facts at all — the unpersisted test entity's shape: zero.
        ReviewerTrustScore nothing = ReviewerTrustScore.derive(null, null);
        assertEquals(0, nothing.score());
        assertEquals(ReviewerTrustScore.TrustTier.NEWCOMER, nothing.tier());
    }

    @Test
    void matureTrustedReviewerReachesTheTopTier() {
        // 5 verified transactions (40) + 180 days of history (20) + 10 helpful
        // votes (40) = 100 — the plan's «مراجع موثوق» in full.
        ReviewerTrustScore score = ReviewerTrustScore.derive(
                new ReviewerStats(5L, 20L, 10L), Duration.ofDays(180));
        assertEquals(100, score.score());
        assertEquals(ReviewerTrustScore.TrustTier.TRUSTED, score.tier());
    }

    @Test
    void eachComponentCapsIndependently() {
        // Double the cap inputs: the components stop at their ceilings — the
        // score never exceeds 100 and no single feed can crown a reviewer.
        ReviewerTrustScore score = ReviewerTrustScore.derive(
                new ReviewerStats(50L, 500L, 999L), Duration.ofDays(3650));
        assertEquals(100, score.score());
        assertEquals(ReviewerTrustScore.TrustTier.TRUSTED, score.tier());
    }

    @Test
    void transactionsAloneReachEstablishedNotTrusted() {
        // 5 verified reviews = 40 (transactions maxed) with zero age and zero
        // votes: ESTABLISHED — history and community endorsement are the
        // other two feeds the plan names, neither can be skipped to TRUSTED.
        ReviewerTrustScore score = ReviewerTrustScore.derive(
                new ReviewerStats(5L, 0L, 0L), Duration.ZERO);
        assertEquals(40, score.score());
        assertEquals(ReviewerTrustScore.TrustTier.ESTABLISHED, score.tier());
    }

    @Test
    void helpfulVotesAloneReachEstablishedNotTrusted() {
        ReviewerTrustScore score = ReviewerTrustScore.derive(
                new ReviewerStats(0L, 0L, 10L), Duration.ZERO);
        assertEquals(40, score.score());
        assertEquals(ReviewerTrustScore.TrustTier.ESTABLISHED, score.tier());
    }

    @Test
    void historyAloneStaysNewcomer() {
        // Even ten years of age caps at 20 points — age alone never buys
        // ESTABLISHED: the account that never transacted and never helped.
        ReviewerTrustScore score = ReviewerTrustScore.derive(
                new ReviewerStats(0L, 0L, 0L), Duration.ofDays(3650));
        assertEquals(20, score.score());
        assertEquals(ReviewerTrustScore.TrustTier.NEWCOMER, score.tier());
    }

    @Test
    void tierFloorsAnswerExactly() {
        // 90 days (10) + 2 verified (16) + 1 vote (4) = 30 exactly — the
        // ESTABLISHED floor itself; one day short of the age point (69+... )
        // stays NEWCOMER.
        assertEquals(ReviewerTrustScore.TrustTier.ESTABLISHED,
                ReviewerTrustScore.derive(new ReviewerStats(2L, 0L, 1L), Duration.ofDays(90)).tier());
        assertEquals(30, ReviewerTrustScore.derive(new ReviewerStats(2L, 0L, 1L), Duration.ofDays(90)).score());
        assertEquals(ReviewerTrustScore.TrustTier.NEWCOMER,
                ReviewerTrustScore.derive(new ReviewerStats(2L, 0L, 1L), Duration.ofDays(89)).tier());
        // 5 verified (40) + 5 votes (20) + 90 days (10) = 70 exactly — the
        // TRUSTED floor itself; one day short (69) stays ESTABLISHED.
        assertEquals(ReviewerTrustScore.TrustTier.TRUSTED,
                ReviewerTrustScore.derive(new ReviewerStats(5L, 0L, 5L), Duration.ofDays(90)).tier());
        assertEquals(70, ReviewerTrustScore.derive(new ReviewerStats(5L, 0L, 5L), Duration.ofDays(90)).score());
        assertEquals(ReviewerTrustScore.TrustTier.ESTABLISHED,
                ReviewerTrustScore.derive(new ReviewerStats(5L, 0L, 5L), Duration.ofDays(89)).tier());
    }

    @Test
    void theScoreIsMonotonicInEveryFeed() {
        ReviewerTrustScore base = ReviewerTrustScore.derive(
                new ReviewerStats(1L, 0L, 1L), Duration.ofDays(30));
        ReviewerTrustScore moreVerified = ReviewerTrustScore.derive(
                new ReviewerStats(2L, 0L, 1L), Duration.ofDays(30));
        ReviewerTrustScore moreHelpful = ReviewerTrustScore.derive(
                new ReviewerStats(1L, 0L, 2L), Duration.ofDays(30));
        ReviewerTrustScore older = ReviewerTrustScore.derive(
                new ReviewerStats(1L, 0L, 1L), Duration.ofDays(60));
        assertEquals(base.score() + ReviewerTrustScore.PER_VERIFIED_POINTS, moreVerified.score());
        assertEquals(base.score() + ReviewerTrustScore.PER_HELPFUL_POINT, moreHelpful.score());
        assertEquals(base.score() + 3, older.score()); // 30 extra days / 9 = 3 points
    }
}
