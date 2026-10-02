package com.marketplace.catalog;

import com.marketplace.shared.api.ReviewStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W3 (yelp-level plan §5 — G18): the composite formula's own acceptance
 * criteria, pinned at the pure-function level — «يفضّل مكتملًا موثقًا نشطًا
 * على مكتمل ناقص صامت عند تساوي النجوم» decomposed into its four factors
 * and their tie-breaks.
 */
class ListingRankingFormulaTest {

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    private static ReviewStats stats(double average, long count) {
        return new ReviewStats(UUID.randomUUID(), average, count);
    }

    @Test
    void noVerifiedReviews_scoreTheHonestZero() {
        double score = ListingRankingFormula.score(null, 100, NOW.minusSeconds(60), NOW);
        assertThat(score).isZero();
    }

    @Test
    void equalStars_moreReviewsOutrankFewer() {
        // The Wilson-style damping: 4.0 × log1p(500) beats 4.0 × log1p(1).
        double many = ListingRankingFormula.score(stats(4.0, 500), 100, NOW, NOW);
        double few = ListingRankingFormula.score(stats(4.0, 1), 100, NOW, NOW);
        assertThat(many).isGreaterThan(few);
    }

    @Test
    void equalStarsAndVolume_completenessBreaksTheTie() {
        double complete = ListingRankingFormula.score(stats(4.0, 10), 100, NOW, NOW);
        double lacking = ListingRankingFormula.score(stats(4.0, 10), 25, NOW, NOW);
        assertThat(complete).isGreaterThan(lacking);
    }

    @Test
    void equalStarsVolumeCompleteness_recencyBreaksTheTie() {
        double fresh = ListingRankingFormula.score(stats(4.0, 10), 100, NOW, NOW);
        double stale = ListingRankingFormula.score(stats(4.0, 10), 100,
                NOW.minus(java.time.Duration.ofDays(90)), NOW);
        assertThat(fresh).isGreaterThan(stale);
    }

    @Test
    void aFiveStarMinorityCannotOutrankAFourStarMajority() {
        // The product's own credibility rule: one perfect review (5.0×log1p(1))
        // loses to a hundred solid fours (4.0×log1p(100)) — log1p's damping
        // is exactly the guard the plan drew it for.
        double lone = ListingRankingFormula.score(stats(5.0, 1), 100, NOW, NOW);
        double crowd = ListingRankingFormula.score(stats(4.0, 100), 100, NOW, NOW);
        assertThat(crowd).isGreaterThan(lone);
    }

    @Test
    void recencyHalfLife_decaysSmoothlyAndNeverReachesZero() {
        double day0 = ListingRankingFormula.score(stats(4.0, 10), 100, NOW, NOW);
        double day30 = ListingRankingFormula.score(stats(4.0, 10), 100,
                NOW.minus(java.time.Duration.ofDays(30)), NOW);
        double day90 = ListingRankingFormula.score(stats(4.0, 10), 100,
                NOW.minus(java.time.Duration.ofDays(90)), NOW);
        // Day 30 is half of day 0; day 90 a quarter of it (the named
        // half-life's own arithmetic); nothing ever reaches zero.
        assertThat(day30).isCloseTo(day0 / 2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(day90).isCloseTo(day0 / 4.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(day90).isPositive();
    }

    @Test
    void theProductNeverViolatesTheSchemaFloor() {
        // Every factor is non-negative; a maximal-age listing still carries
        // a positive recency and the score cannot go below V92's CHECK floor.
        double oldest = ListingRankingFormula.score(stats(1.0, 1), 0,
                NOW.minus(java.time.Duration.ofDays(3650)), NOW);
        assertThat(oldest).isGreaterThanOrEqualTo(0.0);
    }
}
