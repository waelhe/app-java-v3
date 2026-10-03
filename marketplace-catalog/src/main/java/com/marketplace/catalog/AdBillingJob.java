package com.marketplace.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the periodic
 * debit — the plan's «وظيفة خصم دورية». Daily at 04:45 UTC, strictly
 * after the ranking job (03:30) and the event-publication cleanup
 * (03:00) — the billing reads the day the ranking already ranked and
 * settles COMPLETE UTC days only (the horizon is exclusive today).
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
    private final Clock clock;

    public AdBillingJob(AdCampaignRepository campaignRepository,
                        AdBillingBatchExecutor batchExecutor,
                        Clock clock) {
        this.campaignRepository = campaignRepository;
        this.batchExecutor = batchExecutor;
        this.clock = clock;
    }

    /**
     * Runs daily (the plan's cadence — «وظيفة خصم دورية», one settle per
     * day's window). Catch-up is structural: a campaign left behind by
     * downtime settles its whole open span as ONE window on the next run
     * (the charge's {@code window_start} is the span's own start).
     */
    @Scheduled(cron = "0 45 4 * * ?", zone = "UTC")
    public void settleOpenWindows() {
        var today = AdBillingBatchExecutor.todayUtc(clock);
        List<UUID> candidates = campaignRepository.findBillableBefore(today);
        int settled = 0;
        int overlaps = 0;
        for (UUID campaignId : candidates) {
            try {
                batchExecutor.settleOneCampaign(campaignId, today);
                settled++;
            } catch (DataIntegrityViolationException overlapLost) {
                overlaps++;
                log.info("Ad billing overlap: campaign {}'s window was settled by a concurrent run "
                                + "(the deterministic backstop rejected this one) — one window, one charge: {}",
                        campaignId, overlapLost.getMostSpecificCause().getMessage());
            }
        }
        log.info("Ad billing run over {} candidates: {} settled, {} overlaps, horizon {}",
                candidates.size(), settled, overlaps, today);
    }
}
