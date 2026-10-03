package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the provider's rating
 * histogram — the «توزيع نجوم» block the wave's acceptance criterion names
 * («صفحة مزود كاملة: ساعات + خدمات + توزيع نجوم + إشارتا التقييم»).
 *
 * <p><b>All five buckets always present (1..5), zeros included:</b> a
 * histogram that omits empty buckets leaves the renderer guessing whether
 * "no 1-star row" means zero or unknown — the exact ambiguity the
 * {@code rating_general_count = 0} "exact rather than unknown" rule (W1)
 * exists to prevent. The constructor-fills enforce the complete shape.
 *
 * <p>Like {@link ReviewStats}, the distribution is always recomputed from
 * the reviews table — never stored — so it cannot drift from the source
 * of truth.
 */
public record RatingDistribution(
        UUID providerId,
        List<RatingBucket> buckets
) {

    /** One histogram bar: the star value and its live published count. */
    public record RatingBucket(int rating, long count) {
    }

    /**
     * Builds the complete histogram over a sparse {(rating, count)} input:
     * every rating 1..5 appears exactly once, missing ratings carry 0.
     */
    public static RatingDistribution of(UUID providerId, List<RatingBucket> sparse) {
        long[] counts = new long[6]; // index 1..5 — index 0 unused, ratings are 1-based
        for (RatingBucket bucket : sparse) {
            counts[bucket.rating()] = bucket.count();
        }
        List<RatingBucket> complete = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(rating -> new RatingBucket(rating, counts[rating]))
                .toList();
        return new RatingDistribution(providerId, List.copyOf(complete));
    }

    /**
     * W2 (§4.1 — the OPEN mode's single merged signal): the element-wise
     * sum of two complete histograms — the display law's own arithmetic
     * («متوسط واحد يجمع المراجعات العامة والموثقة معًا»), applied to the
     * bars the way the page's weighted mean applies to the average. Both
     * inputs are complete (1..5, zeros included) by construction, so the
     * merge is a plain per-bucket sum.
     */
    public static RatingDistribution merge(RatingDistribution verified, RatingDistribution general) {
        List<RatingBucket> merged = new java.util.ArrayList<>(5);
        for (int rating = 1; rating <= 5; rating++) {
            long count = bucket(verified, rating) + bucket(general, rating);
            merged.add(new RatingBucket(rating, count));
        }
        return new RatingDistribution(verified.providerId(), List.copyOf(merged));
    }

    private static long bucket(RatingDistribution distribution, int rating) {
        if (distribution == null) {
            return 0;
        }
        return distribution.buckets().stream()
                .filter(b -> b.rating() == rating)
                .mapToLong(RatingBucket::count)
                .findFirst().orElse(0L);
    }
}
