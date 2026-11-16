package com.marketplace.identity;

import com.marketplace.shared.api.ReviewerStats;

import java.util.ArrayList;
import java.util.List;

/**
 * W4 (yelp-level plan §5 — G29, "أوسمة المراجع"): the reviewer's credibility
 * badges — DERIVED reads over the two measured facts the plan names
 * («موثّق المعاملات، أصوات مفيد تراكمية»), never stored state: the badge
 * list is recomputed from {@link ReviewerStats} on every page render, so it
 * can never drift from the facts (the same recompute-is-truth rule the
 * provider rating aggregates follow).
 *
 * <p>The derivation rules (thresholds are THIS wave's documented choice —
 * the plan names the two badge families without numbers; they live here,
 * and only here, as named constants a future owner decision can move to the
 * {@code system_settings} control layer the W0 wave established):
 * <ul>
 *   <li>{@link #VERIFIED_REVIEWER} — at least one PUBLISHED verified
 *       (BOOKING-origin) review: the reviewer has rated completed
 *       transactions, the strongest trust signal the dual-mode design
 *       owns (§4.4: the verified badge rides {@code origin});</li>
 *   <li>{@link #HELPFUL_REVIEWER} — at least {@value #HELPFUL_VOTE_THRESHOLD}
 *       cumulative helpful votes received on his reviews: the community's
 *       endorsement the W1 vote data measures (§4.5: "إشارة الجودة التي
 *       تتغذى عليها درجة ثقة المراجع").</li>
 * </ul>
 */
public enum ReviewerBadge {

    /** «موثّق المعاملات» — has published reviews born from completed bookings. */
    VERIFIED_REVIEWER,

    /** «أصوات مفيد تراكمية» — his reviews accumulated community helpful votes. */
    HELPFUL_REVIEWER;

    /** The verified badge's floor: one published verified review IS the fact. */
    static final long VERIFIED_REVIEW_THRESHOLD = 1L;

    /** The helpful badge's floor — a named, documented choice (see class javadoc). */
    static final long HELPFUL_VOTE_THRESHOLD = 10L;

    /**
     * The derivation itself — the plan's two badge families over the
     * port's facts, in stable declaration order (a deterministic response
     * shape, never a set's iteration order).
     */
    static List<ReviewerBadge> derive(ReviewerStats stats) {
        List<ReviewerBadge> badges = new ArrayList<>(2);
        if (stats != null && stats.verifiedReviewCount() >= VERIFIED_REVIEW_THRESHOLD) {
            badges.add(VERIFIED_REVIEWER);
        }
        if (stats != null && stats.helpfulVoteCount() >= HELPFUL_VOTE_THRESHOLD) {
            badges.add(HELPFUL_REVIEWER);
        }
        return List.copyOf(badges);
    }
}
