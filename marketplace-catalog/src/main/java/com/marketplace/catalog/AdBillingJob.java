package com.marketplace.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24) + C.6 (the Moments
 * migration): the periodic debit — the plan's «وظيفة خصم دورية», now a
 * listener on Modulith Moments' {@link DayHasPassed} instead of the former
 * manual {@code @Scheduled} cron (the compliance plan's own target: «الزمن
 * بالأحداث لا بالمجدولات اليدوية»). It settles COMPLETE UTC days only —
 * the horizon is exclusive today, derived from the event's own payload: the
 * day AFTER the day that just passed.
 *
 * <p><b>The Moments cadence (the C.6 decision, stated honestly):</b> the run
 * follows the day boundary — Moments publishes {@link DayHasPassed} at each
 * midnight UTC (the default {@code spring.modulith.moments.zone-id}), where
 * the former cron fired at 04:45. The settle no longer reads any wall clock:
 * {@code DayHasPassed.getDate()} IS the completed day, so the horizon
 * ({@code getDate().plusDays(1)}) is a pure function of the event — the
 * whole journey deterministic under the {@code TimeMachine} (the C.6 gate).
 * The ranking job now ticks on the SAME midnight event; both listeners run
 * synchronously in publication order on Moments' scheduler thread — the
 * exact serialization the jobs already had on the default single-threaded
 * {@code TaskScheduler} — and the settle reads only the campaigns' own
 * counters (impressions/clicks dailies), never the ranking's scores: no
 * data dependency, only the old off-peak scheduling habit.
 *
 * <p><b>The orchestration shape is the {@code ListingRankingJob}'s:</b> a
 * thin loop over the executor's bounded {@code REQUIRES_NEW} work units.
 * The candidate list is advisory (each settle re-reads fresh state); an
 * overlapping run's loser surfaces as the charge insert's
 * {@link DataIntegrityViolationException} — the EXPECTED deterministic
 * outcome (one window, one charge), logged and skipped so the run keeps
 * settling the other campaigns. Any other failure propagates: the job
 * fails loudly and the next run retries the unsettled windows (the
 * marker never advanced).</p>
 */
@Component
public class AdBillingJob {

    private static final Logger log = LoggerFactory.getLogger(AdBillingJob.class);

    private final AdCampaignRepository campaignRepository;
    private final AdBillingBatchExecutor batchExecutor;

    public AdBillingJob(AdCampaignRepository campaignRepository,
                        AdBillingBatchExecutor batchExecutor) {
        this.campaignRepository = campaignRepository;
        this.batchExecutor = batchExecutor;
    }

    /**
     * The daily tick. Catch-up is structural: a campaign left behind by
     * downtime settles its whole open span as ONE window on the next run
     * (the charge's {@code window_start} is the span's own start).
     */
    @EventListener
    void on(DayHasPassed event) {
        settleWindowsThrough(event.getDate().plusDays(1));
    }

    /**
     * Settles every campaign's open windows strictly before the horizon (a
     * complete-UTC-days-only boundary — the exclusive {@code today} of the
     * midnight the event fired, carried by the event itself).
     */
    void settleWindowsThrough(LocalDate horizon) {
        List<UUID> candidates = campaignRepository.findBillableBefore(horizon);
        int settled = 0;
        int overlaps = 0;
        for (UUID campaignId : candidates) {
            try {
                batchExecutor.settleOneCampaign(campaignId, horizon);
                settled++;
            } catch (DataIntegrityViolationException violation) {
                // CodeRabbit W5 r1, adopted: ONLY the window-uniqueness
                // violation is the expected deterministic outcome (one
                // window, one charge — the loser's rollback). Any other
                // integrity failure is a billing bug this run must surface
                // loudly, never swallow as a fake overlap.
                if (!isWindowUniquenessViolation(violation)) {
                    throw violation;
                }
                overlaps++;
                log.info("Ad billing overlap: campaign {}'s window was settled by a concurrent run "
                                + "(the deterministic backstop rejected this one) — one window, one charge",
                        campaignId);
            }
        }
        log.info("Ad billing run over {} candidates: {} settled, {} overlaps, horizon {}",
                candidates.size(), settled, overlaps, horizon);
    }

    /**
     * The window-uniqueness backstop's own signature: the constraint name
     * in the violation's most specific cause — the same
     * {@code isWeekendRuleInsertRace} discrimination the pricing insert-race
     * retry uses (a DIVE carries more than uniqueness; blind catching
     * would hide real billing bugs).
     */
    private static boolean isWindowUniquenessViolation(DataIntegrityViolationException violation) {
        Throwable cause = violation;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null && message.contains(WINDOW_UNIQUENESS_CONSTRAINT)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** The charge table's window-identity constraint — the overlap backstop's own name. */
    static final String WINDOW_UNIQUENESS_CONSTRAINT = "uk_ad_billing_charges_campaign_window";
}
