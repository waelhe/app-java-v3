package com.marketplace.catalog;

import com.marketplace.shared.api.AdWindowBilledEvent;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5 (yelp-level plan §5 — G24): the settle's own arithmetic — the frozen
 * consumption, the budget cap («حملة بميزانية تنتهي بنفادها»), the
 * marker's atomic advance, the zero window's honest skip, and the
 * AFTER_COMMIT event pair that carries the frozen truth to payments and
 * the ledger.
 */
class AdBillingBatchExecutorTest {

    private final AdCampaignRepository campaignRepository = mock(AdCampaignRepository.class);
    private final AdBillingChargeRepository chargeRepository = mock(AdBillingChargeRepository.class);
    private final ListingViewsDailyRepository viewsRepository = mock(ListingViewsDailyRepository.class);
    private final AdClickDailyRepository clicksRepository = mock(AdClickDailyRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private static final Instant NOW = Instant.parse("2026-10-03T05:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.parse("2026-10-03");

    private AdBillingBatchExecutor executor;
    private final UUID providerId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executor = new AdBillingBatchExecutor(campaignRepository, chargeRepository,
                viewsRepository, clicksRepository, eventPublisher, CLOCK);
    }

    private AdCampaign campaignStartingDaysAgo(long budget, long clickPrice, long impressionPrice, int daysAgo) {
        return AdCampaign.start(providerId, listingId, budget, clickPrice, impressionPrice, "SAR",
                NOW.minus(Duration.ofDays(daysAgo)), null);
    }

    private void windowTraffic(long impressions, long clicks) {
        when(viewsRepository.sumViewsForListingBetween(eq(listingId), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(impressions);
        when(clicksRepository.sumClicksForCampaignBetween(any(UUID.class), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(clicks);
    }

    @Test
    void settle_freezesTheConsumptionAndPublishesTheFrozenTruth() {
        // 3-day-old campaign: the whole open span settles as ONE window.
        AdCampaign campaign = campaignStartingDaysAgo(50000L, 100L, 5L, 3);
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        windowTraffic(1200L, 40L); // 1200×5 + 40×100 = 6000 + 4000 = 10000

        executor.settleOneCampaign(campaign.getId(), TODAY);

        ArgumentCaptor<AdBillingCharge> charge = ArgumentCaptor.forClass(AdBillingCharge.class);
        verify(chargeRepository).saveAndFlush(charge.capture());
        assertEquals(10000L, charge.getValue().getAmountCents());
        assertEquals(1200L, charge.getValue().getImpressions());
        assertEquals(40L, charge.getValue().getClicks());
        // The birth day is FREE (the daily-grain rule): the window starts at
        // the start date's NEXT day — 2026-09-30 + 1 = 2026-10-01.
        assertEquals(LocalDate.parse("2026-10-01"), charge.getValue().getWindowStart());
        assertEquals(TODAY, charge.getValue().getWindowEnd());
        assertEquals(10000L, campaign.getConsumedCents());
        assertEquals(TODAY, campaign.getBilledThrough());
        assertEquals(AdCampaignStatus.ACTIVE, campaign.getStatus()); // budget remains

        ArgumentCaptor<AdWindowBilledEvent> event = ArgumentCaptor.forClass(AdWindowBilledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertEquals(campaign.getId(), event.getValue().campaignId());
        assertEquals(10000L, event.getValue().amountCents());
        assertEquals(charge.getValue().getWindowStart(), event.getValue().windowStart());
        // The ledger twin's penny-for-penny law: the event carries the SAME
        // frozen amount the charge froze («القيد يوازن الميزانية المخصومة
        // فلسًا بفلس») — no recomputation anywhere downstream.
        assertEquals(charge.getValue().getAmountCents(), event.getValue().amountCents());
    }

    @Test
    void settle_capsAtTheRemainingBudgetAndEndsTheCampaignByExhaustion() {
        // Budget 10000, prices produce 100000: the charge is CAPPED at the
        // remaining budget and the campaign ENDS («حملة بميزانية تنتهي
        // بنفادها»).
        AdCampaign campaign = campaignStartingDaysAgo(10000L, 1000L, 50L, 2);
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        windowTraffic(1000L, 100L); // 1000×50 + 100×1000 = 50000 + 100000 = 150000 >> 10000

        executor.settleOneCampaign(campaign.getId(), TODAY);

        ArgumentCaptor<AdBillingCharge> charge = ArgumentCaptor.forClass(AdBillingCharge.class);
        verify(chargeRepository).saveAndFlush(charge.capture());
        assertEquals(10000L, charge.getValue().getAmountCents());
        assertEquals(10000L, campaign.getConsumedCents());
        assertEquals(AdCampaignStatus.ENDED, campaign.getStatus());
        // The ENDED campaign's boost death is a cache event — the ranked
        // pages must not serve yesterday's order.
        verify(eventPublisher).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void settle_advancesTheMarkerAloneOnAZeroWindow() {
        // Zero traffic: no charge row, no billing event — the marker advanced
        // alone (the honest «مجاني» day). Two days back so the birth-day-free
        // rule leaves one complete billable day ([TODAY-1, TODAY)).
        AdCampaign campaign = campaignStartingDaysAgo(10000L, 100L, 5L, 2);
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        windowTraffic(0L, 0L);

        executor.settleOneCampaign(campaign.getId(), TODAY);

        verify(chargeRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(AdWindowBilledEvent.class));
        assertEquals(TODAY, campaign.getBilledThrough());
        assertEquals(0L, campaign.getConsumedCents());
        assertEquals(AdCampaignStatus.ACTIVE, campaign.getStatus());
    }

    @Test
    void settle_skipsThePausedCampaignEntirely() {
        AdCampaign campaign = campaignStartingDaysAgo(10000L, 100L, 5L, 3);
        campaign.pause();
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));

        executor.settleOneCampaign(campaign.getId(), TODAY);

        verify(chargeRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any(AdWindowBilledEvent.class));
        // The dark gap never bills: the marker did not move (the birth day's
        // next day — the birth day itself is free by the daily-grain rule).
        assertEquals(LocalDate.parse("2026-10-01"), campaign.getBilledThrough());
    }

    @Test
    void settle_skipsTheExhaustedCampaignAndTheSettledMarker() {
        AdCampaign exhausted = campaignStartingDaysAgo(1000L, 100L, 5L, 5);
        exhausted.consume(1000L); // budget fully consumed → ENDED
        when(campaignRepository.findById(exhausted.getId())).thenReturn(Optional.of(exhausted));
        executor.settleOneCampaign(exhausted.getId(), TODAY);
        verify(chargeRepository, never()).saveAndFlush(any());

        AdCampaign settled = campaignStartingDaysAgo(10000L, 100L, 5L, 1);
        settled.markBilledThrough(TODAY); // marker already at the horizon
        when(campaignRepository.findById(settled.getId())).thenReturn(Optional.of(settled));
        executor.settleOneCampaign(settled.getId(), TODAY);
        verify(chargeRepository, never()).saveAndFlush(any());
    }

    @Test
    void settle_endsByDurationAndSettlesOnlyTheTail() {
        // The campaign ended 2 days ago by duration: the tail settles through
        // the ends date only, and the campaign flips ENDED.
        AdCampaign campaign = AdCampaign.start(providerId, listingId, 100000L, 100L, 5L, "SAR",
                NOW.minus(Duration.ofDays(4)), NOW.minus(Duration.ofDays(2)));
        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        windowTraffic(500L, 10L); // 500×5 + 10×100 = 2500 + 1000 = 3500

        executor.settleOneCampaign(campaign.getId(), TODAY);

        assertEquals(AdCampaignStatus.ENDED, campaign.getStatus());
        ArgumentCaptor<AdBillingCharge> charge = ArgumentCaptor.forClass(AdBillingCharge.class);
        verify(chargeRepository).saveAndFlush(charge.capture());
        // The window ends at the ends date's next day — NOT today.
        assertEquals(LocalDate.parse("2026-10-02"), charge.getValue().getWindowEnd());
        assertEquals(3500L, charge.getValue().getAmountCents());
    }

    @Test
    void settle_goneCampaignIsASilentNoOp() {
        when(campaignRepository.findById(UUID.randomUUID())).thenReturn(Optional.empty());
        executor.settleOneCampaign(UUID.randomUUID(), TODAY);
        verify(chargeRepository, never()).saveAndFlush(any());
    }
}
