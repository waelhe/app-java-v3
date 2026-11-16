package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W2 (yelp-level plan §5 — the business page): the rating-histogram
 * record's own unit guards — the complete-bucket contract (all five
 * stars always present, zeros explicit — «exact rather than unknown»),
 * the sparse-fill construction, and OPEN mode's element-wise merge law
 * (§4.1: «متوسط واحد يجمع المراجعات العامة والموثقة معًا», applied to the
 * bars the way the page's weighted mean applies to the average).
 */
class RatingDistributionTest {

    private static final UUID PROVIDER_ID = UUID.randomUUID();

    @Test
    void of_fillsTheFiveBucketsWithExplicitZeros() {
        RatingDistribution distribution = RatingDistribution.of(PROVIDER_ID, List.of(
                new RatingDistribution.RatingBucket(5, 10),
                new RatingDistribution.RatingBucket(3, 2)));

        assertThat(distribution.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(distribution.buckets()).hasSize(5);
        assertThat(distribution.buckets())
                .extracting(RatingDistribution.RatingBucket::rating)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(distribution.buckets())
                .extracting(RatingDistribution.RatingBucket::count)
                .containsExactly(0L, 0L, 2L, 0L, 10L);
    }

    @Test
    void of_emptySparseInput_yieldsTheAllZeroHistogram() {
        RatingDistribution distribution = RatingDistribution.of(PROVIDER_ID, List.of());

        assertThat(distribution.buckets()).hasSize(5);
        assertThat(distribution.buckets())
                .allSatisfy(bucket -> assertThat(bucket.count()).isZero());
    }

    @Test
    void merge_sumsTheBucketsElementWise() {
        RatingDistribution verified = RatingDistribution.of(PROVIDER_ID, List.of(
                new RatingDistribution.RatingBucket(5, 10),
                new RatingDistribution.RatingBucket(4, 2)));
        RatingDistribution general = RatingDistribution.of(PROVIDER_ID, List.of(
                new RatingDistribution.RatingBucket(5, 150),
                new RatingDistribution.RatingBucket(4, 6),
                new RatingDistribution.RatingBucket(2, 1)));

        RatingDistribution merged = RatingDistribution.merge(verified, general);

        assertThat(merged.providerId()).isEqualTo(PROVIDER_ID);
        assertThat(merged.buckets())
                .extracting(RatingDistribution.RatingBucket::count)
                .containsExactly(0L, 1L, 0L, 8L, 160L);
    }

    @Test
    void merge_nullGeneral_yieldsTheVerifiedBarsUnchanged() {
        RatingDistribution verified = RatingDistribution.of(PROVIDER_ID, List.of(
                new RatingDistribution.RatingBucket(5, 7)));

        RatingDistribution merged = RatingDistribution.merge(verified, null);

        assertThat(merged.buckets())
                .extracting(RatingDistribution.RatingBucket::count)
                .containsExactly(0L, 0L, 0L, 0L, 7L);
    }
}
