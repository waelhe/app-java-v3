package com.marketplace.catalog;

import com.marketplace.shared.api.AdWindowBilledEvent;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the daily
 * billing run's ONE-CAMPAIGN transaction boundary — the
 * {@code ListingRankingBatchExecutor} pattern (one bounded
 * {@code REQUIRES_NEW} transaction per work unit, its own persistence
 * context) applied to the settle.
 *
 * <p><b>The settle, in order:</b> re-read the campaign inside THIS
 * transaction (fresh state — the job's candidate list is advisory);
 * a PAUSED campaign is skipped (the dark gap never bills); a campaign
 * past its {@code ends_at} is ended by duration and settles its tail
 * through the ends date; an exhausted campaign settles nothing; the
 * open window {@code [billedThrough, horizon)} is frozen — impressions
 * from the existing {@code listing_views_daily} (the plan's «ظهور من
 * listing_views_daily القائم»), clicks from {@code ad_clicks_daily} —
 * the amount CAPPED at the remaining budget («حملة بميزانية تنتهي
 * بنفادها»), and the immutable charge row, the {@code consumed_cents}
 * move and the {@code billed_through} advance all commit in THIS one
 * transaction (the atomicity the plan's window identity demands).</p>
 *
 * <p><b>The deterministic identity:</b> the charge's
 * {@code (campaign_id, window_start)} UNIQUE index (V103) makes a
 * re-run or two overlapping schedules for one window produce at most
 * ONE row — the loser's {@link org.springframework.dao.DataIntegrityViolationException}
 * rolls its whole transaction back (the advance rides with it), and the
 * next run's fresh read finds the winner's advanced marker and skips.
 * The {@link AdWindowBilledEvent} published here rides the AFTER_COMMIT
 * listener contract ({@code @ApplicationModuleListener}), so payments
 * (the intent) and ledger (the AD_DEBIT entry) each land exactly once
 * per frozen window, each with its own structural idempotency key.</p>
 */
@Component
public class AdBillingBatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(AdBillingBatchExecutor.class);

    private final AdCampaignRepository campaignRepository;
    private final AdBillingChargeRepository chargeRepository;
    private final ListingViewsDailyRepository viewsRepository;
    private final AdClickDailyRepository clicksRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public AdBillingBatchExecutor(AdCampaignRepository campaignRepository,
                                  AdBillingChargeRepository chargeRepository,
                                  ListingViewsDailyRepository viewsRepository,
                                  AdClickDailyRepository clicksRepository,
                                  ApplicationEventPublisher eventPublisher,
                                  Clock clock) {
        this.campaignRepository = campaignRepository;
        this.chargeRepository = chargeRepository;
        this.viewsRepository = viewsRepository;
        this.clicksRepository = clicksRepository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Settles ONE campaign's open window in its own transaction.
     *
     * @param campaignId the job's candidate (re-read fresh inside the tx)
     * @param today      the run's UTC today — the exclusive horizon (the
     *                   run bills COMPLETE UTC days only)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleOneCampaign(UUID campaignId, LocalDate today) {
        AdCampaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null || campaign.getStatus() == AdCampaignStatus.PAUSED) {
            return; // gone or dark — the dark gap never bills
        }

        // The duration's own end: a campaign past its ends_at settles its
        // tail through the ends date and then never bills again (the
        // advanced marker sits past the horizon forever after).
        LocalDate horizon = today;
        if (campaign.getEndsAt() != null) {
            LocalDate endsDate = LocalDate.ofInstant(campaign.getEndsAt(), ZoneOffset.UTC);
            if (today.isAfter(endsDate)) {
                campaign.endByDuration();
            }
            LocalDate tailEnd = endsDate.plusDays(1);
            if (tailEnd.isBefore(horizon)) {
                horizon = tailEnd;
            }
        }

        if (!campaign.hasRemainingBudget()) {
            return; // exhausted — nothing more can bill
        }
        LocalDate windowStart = campaign.getBilledThrough();
        if (!windowStart.isBefore(horizon)) {
            return; // no unsettled complete day
        }

        // The freeze: the consumption the window actually saw.
        long impressions = viewsRepository.sumViewsForListingBetween(
                campaign.getListingId(), windowStart, horizon);
        long clicks = clicksRepository.sumClicksForCampaignBetween(
                campaign.getId(), windowStart, horizon);
        long computed = Math.addExact(
                Math.multiplyExact(impressions, campaign.getImpressionPriceCents()),
                Math.multiplyExact(clicks, campaign.getClickPriceCents()));
        long remaining = campaign.getBudgetCents() - campaign.getConsumedCents();
        long amount = Math.min(computed, remaining);

        // The marker's atomic advance — same transaction as the charge
        // insert below (and the consume move): a crash or a lost overlap
        // rolls ALL of it back, so a half-settled window cannot exist.
        campaign.markBilledThrough(horizon);
        if (amount <= 0) {
            // The zero window: no charge row, no event, no ledger entry —
            // the marker advanced alone (the honest «مجاني» day).
            log.debug("Ad window [{}, {}) of campaign {} settled at zero consumption — marker advanced",
                    windowStart, horizon, campaignId);
            return;
        }

        AdBillingCharge charge = AdBillingCharge.freeze(campaign.getId(), windowStart, horizon,
                impressions, clicks, amount, campaign.getCurrency());
        // saveAndFlush: the INSERT (and a lost overlap's constraint
        // violation) surface HERE, inside this transaction, before the
        // event could ever be published for a row the loser rolled back.
        chargeRepository.saveAndFlush(charge);
        campaign.consume(amount);

        // The AFTER_COMMIT pair — payments (the intent) and ledger (the
        // AD_DEBIT) each land exactly once for this frozen window, each
        // by its own structural key: the intent's idempotency key and
        // the ledger's deterministic source id.
        eventPublisher.publishEvent(new AdWindowBilledEvent(
                campaign.getId(), campaign.getProviderId(), windowStart, horizon,
                impressions, clicks, amount, campaign.getCurrency()));

        // The campaign's own state changed the boost's truth (an ended
        // campaign's listing loses the paid tier) — the ordered pages
        // must not serve yesterday's order: the ranking job's own law.
        if (campaign.getStatus() == AdCampaignStatus.ENDED) {
            eventPublisher.publishEvent(new CacheInvalidationRequested(CatalogService.CATALOG_CACHE_NAMES));
        }
        log.info("Ad window [{}, {}) of campaign {} frozen: {} impressions x {} + {} clicks x {} "
                        + "= {} of {} computed, capped at {} remaining — {} {} billed",
                windowStart, horizon, campaignId, impressions, campaign.getImpressionPriceCents(),
                clicks, campaign.getClickPriceCents(), amount, computed, remaining,
                amount, campaign.getCurrency());
    }

    /** The run's UTC today — one derivation, the job and its tests share it. */
    static LocalDate todayUtc(Clock clock) {
        return LocalDate.now(clock);
    }
}
