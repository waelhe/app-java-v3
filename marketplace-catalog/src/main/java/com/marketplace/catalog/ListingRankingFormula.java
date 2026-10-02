package com.marketplace.catalog;

import java.time.Duration;
import java.time.Instant;

import com.marketplace.shared.api.ReviewStats;

/**
 * W3 (yelp-level plan §5 — the discovery & ranking wave, G18): the
 * composite ranking formula — «تقييم × log كمية × اكتمال × حداثة» — as
 * ONE pure function, the single authority the daily job applies. No
 * storage, no clock of its own: the caller passes the batch's "now" so
 * the whole run shares one instant (the L37 one-read-one-now lesson).
 *
 * <p><b>The four factors and why each one is there:</b>
 * <ul>
 *   <li><b>rating × log1p(count)</b> — the verified pair's Wilson-style
 *       damping: at equal stars, the provider with MORE reviews outranks
 *       the provider with fewer (a single five-star review must not crown
 *       a provider above one holding five hundred fours-and-fives). The
 *       stats are the RECOMPUTED exposed pair (the same law the provider
 *       page's badge reads); a provider with no verified reviews scores
 *       {@code 0.0} — the honest floor, never an exception.</li>
 *   <li><b>completeness</b> — the listing's own L38 score as a fraction:
 *       the plan's «يفضّل مكتملًا على مكتمل ناقص عند تساوي النجوم» is this
 *       factor's own acceptance.</li>
 *   <li><b>recency</b> — a smooth {@code 1/(1 + age/halfLife)} decay over
 *       the listing's creation instant: a 30-day half-life (day 0 → 1.0,
 *       day 30 → 0.5, day 90 → 0.25) — bounded {@code (0, 1]}, never a
 *       cliff, never negative. The half-life is a named constant: the
 *       plan's calibration point, tuned with real usage data.</li>
 * </ul>
 *
 * <p>The product is non-negative by construction (every factor is), which
 * is exactly what V92's CHECK floor declares — the formula cannot produce
 * a value the schema rejects.
 */
final class ListingRankingFormula {

    /** The recency decay's half-life in days — the plan's calibration constant. */
    static final double RECENCY_HALF_LIFE_DAYS = 30.0;

    private ListingRankingFormula() {
    }

    /**
     * The composite score for one listing.
     *
     * @param stats              the provider's recomputed verified pair —
     *                           {@code null} means no verified reviews (the
     *                           honest 0.0, the floor every ranked listing
     *                           carries until its provider earns stars)
     * @param completenessPercent the listing's L38 completeness (0..100)
     * @param createdAt          the listing's creation instant
     * @param now                the run's single shared instant
     */
    static double score(ReviewStats stats, int completenessPercent, Instant createdAt, Instant now) {
        if (stats == null) {
            return 0.0;
        }
        double ratingFactor = stats.averageRating() * Math.log1p(stats.reviewCount());
        double completeness = completenessPercent / 100.0;
        double ageDays = Math.max(0.0, Duration.between(createdAt, now).toMillis() / 86_400_000.0);
        double recency = 1.0 / (1.0 + ageDays / RECENCY_HALF_LIFE_DAYS);
        return ratingFactor * completeness * recency;
    }
}
