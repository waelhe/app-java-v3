package com.marketplace.identity;

import com.marketplace.shared.api.ReviewerStats;

import java.time.Duration;

/**
 * W5 (yelp-level plan §5 — G26, «درجة ثقة للمراجع»): the reviewer's
 * graded trust signal — the plan's own words: «مراجع موثوق (حساب
 * معاملات، تاريخ، أصوات مفيد) مقابل حساب ولد اليوم — لا تمييز». W4's
 * badges answer the binary questions; the score answers the graded one —
 * the difference between «has at least one» and «how much».
 *
 * <p><b>DERIVED, never stored</b> (the W4 {@link ReviewerBadge} rule
 * verbatim — «مشتق أبدًا لا مخزن»): recomputed from the measured facts on
 * every page render, so it can never drift from them.</p>
 *
 * <p><b>The formula is three transparent components</b>, each capped, in
 * the exact order the plan names them («حساب معاملات، تاريخ، أصوات
 * مفيد» — transactions, history, helpful votes):
 * <ul>
 *   <li><b>Transactions</b> (max {@value #VERIFIED_COMPONENT_MAX}): each
 *       published verified review — a rating born from a completed,
 *       paid booking — contributes {@value #PER_VERIFIED_POINTS} points;
 *       five transactions max the component. The strongest single trust
 *       fact the dual-mode design owns.</li>
 *   <li><b>History</b> (max {@value #AGE_COMPONENT_MAX}): one point per
 *       {@value #DAYS_PER_AGE_POINT} days of account age — the army of
 *       day-old accounts the §4.5 anti-abuse gates price in patience;
 *       {@value #AGE_COMPONENT_MAX}×{@value #DAYS_PER_AGE_POINT} days max
 *       the component.</li>
 *   <li><b>Helpful votes</b> (max {@value #HELPFUL_COMPONENT_MAX}): each
 *       cumulative helpful vote the community gave his reviews
 *       contributes {@value #PER_HELPFUL_POINT} points — the quality
 *       signal §4.5 names as the trust score's own feed («إشارة الجودة
 *       التي تتغذى عليها درجة ثقة المراجع»); ten votes max the
 *       component.</li>
 * </ul>
 *
 * <p><b>The tier labels</b> carry the reading, not the arithmetic: a
 * day-old account with zero activity is honestly {@link #NEWCOMER} (0);
 * the mature reviewer with transactions, history and community
 * endorsement reaches {@link #TRUSTED}. The thresholds are THIS wave's
 * documented choice — the plan names the three feeds without numbers;
 * they live here, and only here, as named constants a future owner
 * decision can move to the {@code system_settings} control layer the W0
 * wave established.</p>
 *
 * @param score the 0–100 trust score (the three capped components' sum)
 * @param tier  the score's reading — {@link #NEWCOMER} below
 *              {@value #ESTABLISHED_FLOOR}, {@link #ESTABLISHED} below
 *              {@value #TRUSTED_FLOOR}, {@link #TRUSTED} at or above
 */
public record ReviewerTrustScore(int score, TrustTier tier) {

    /** The transactions component's ceiling. */
    static final int VERIFIED_COMPONENT_MAX = 40;

    /** Points per published verified review. */
    static final int PER_VERIFIED_POINTS = 8;

    /** The history component's ceiling. */
    static final int AGE_COMPONENT_MAX = 20;

    /** Days of account age per point. */
    static final int DAYS_PER_AGE_POINT = 9;

    /** The helpful-votes component's ceiling. */
    static final int HELPFUL_COMPONENT_MAX = 40;

    /** Points per cumulative helpful vote. */
    static final int PER_HELPFUL_POINT = 4;

    /** The ESTABLISHED tier's floor. */
    static final int ESTABLISHED_FLOOR = 30;

    /** The TRUSTED tier's floor. */
    static final int TRUSTED_FLOOR = 70;

    /** The day-old-account reading — the plan's «حساب ولد اليوم». */
    public enum TrustTier { NEWCOMER, ESTABLISHED, TRUSTED }

    /**
     * The derivation itself — the three capped components over the
     * port's measured facts plus the account's age. Null stats (the
     * not-yet-a-reviewer profile) is the honest zero, never a guess.
     */
    static ReviewerTrustScore derive(ReviewerStats stats, Duration accountAge) {
        long verified = stats == null ? 0 : Math.max(0, stats.verifiedReviewCount());
        long helpful = stats == null ? 0 : Math.max(0, stats.helpfulVoteCount());
        long days = accountAge == null ? 0 : Math.max(0, accountAge.toDays());

        int transactions = (int) Math.min(VERIFIED_COMPONENT_MAX, verified * PER_VERIFIED_POINTS);
        int history = (int) Math.min(AGE_COMPONENT_MAX, days / DAYS_PER_AGE_POINT);
        int helpfulness = (int) Math.min(HELPFUL_COMPONENT_MAX, helpful * PER_HELPFUL_POINT);

        int score = Math.min(100, transactions + history + helpfulness);
        return new ReviewerTrustScore(score, tierOf(score));
    }

    private static TrustTier tierOf(int score) {
        if (score >= TRUSTED_FLOOR) {
            return TrustTier.TRUSTED;
        }
        return score >= ESTABLISHED_FLOOR ? TrustTier.ESTABLISHED : TrustTier.NEWCOMER;
    }
}
