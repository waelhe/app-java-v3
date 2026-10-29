package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W5 (yelp-level plan §5 — G24): the campaign owner-facing surface's
 * gates — the creation law (ACTIVE listing + ownership + the
 * single-live-campaign rule + the future duration), the pause/resume
 * state machine with the marker jump, and the ownership-checked reads.
 */
class AdCampaignServiceTest {

    private final AdCampaignRepository campaignRepository = mock(AdCampaignRepository.class);
    private final AdBillingChargeRepository chargeRepository = mock(AdBillingChargeRepository.class);
    private final AdBillingBatchExecutor billingExecutor = mock(AdBillingBatchExecutor.class);
    private final ProviderListingRepository listingRepository = mock(ProviderListingRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ProviderLookupPort providerLookupPort = mock(ProviderLookupPort.class);
    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final Authentication authentication = mock(Authentication.class);

    private static final Instant NOW = Instant.parse("2026-10-03T10:15:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID ownerId = UUID.randomUUID();
    private AdCampaignService service;

    @BeforeEach
    void setUp() {
        service = new AdCampaignService(campaignRepository, chargeRepository, billingExecutor,
                listingRepository, currentUserProvider, providerLookupPort, eventPublisher, CLOCK);
    }

    private ProviderListing activeListing() {
        ProviderListing listing = ProviderListing.create(ownerId, "Title", "Desc", "MAINTENANCE", 25000L);
        listing.activate();
        return listing;
    }

    private void ownedByCaller(ProviderListing listing) {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(listing.getProviderId()))
                .thenReturn(Optional.of(new ProviderSummary(UUID.randomUUID(), "Name", "ACTIVE", ownerId)));
    }

    private void saveReturnsCampaign() {
        when(campaignRepository.save(any(AdCampaign.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_startsAnActiveCampaignWithTheWindowMarkerAtTheStartDay() {
        ProviderListing listing = activeListing();
        when(listingRepository.findById(listing.getId())).thenReturn(Optional.of(listing));
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(listing.getId(), AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.empty());
        ownedByCaller(listing);
        saveReturnsCampaign();

        AdCampaignService.AdCampaignView view = service.create(new AdCampaignService.CreateAdCampaignRequest(
                listing.getId(), 50000L, 100L, 5L, null, NOW.plus(Duration.ofDays(30))), authentication);

        assertEquals(AdCampaignStatus.ACTIVE.name(), view.status());
        assertEquals(50000L, view.budgetCents());
        assertEquals(0L, view.consumedCents());
        assertEquals(50000L, view.remainingCents());
        assertEquals("SAR", view.currency());
        // The window identity: the marker starts at the start date's NEXT
        // UTC day — the birth day itself is FREE (the daily-grain rule:
        // a mid-day birth cannot be separated from the same day's
        // pre-birth views, so charging them would bill what the campaign
        // never bought).
        assertEquals(LocalDate.parse("2026-10-04"), view.billedThrough());
        assertEquals(NOW, view.startsAt());
    }

    @Test
    void create_rejectsAListingThatIsNotActive() {
        ProviderListing draft = ProviderListing.create(ownerId, "T", "D", "MAINTENANCE", 25000L);
        when(listingRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThrows(ConflictException.class, () -> service.create(
                new AdCampaignService.CreateAdCampaignRequest(draft.getId(), 50000L, 100L, 5L, null, null),
                authentication));
        verify(campaignRepository, never()).save(any(AdCampaign.class));
    }

    @Test
    void create_rejectsSomeoneElsesListing() {
        ProviderListing listing = activeListing();
        when(listingRepository.findById(listing.getId())).thenReturn(Optional.of(listing));
        UUID stranger = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(stranger);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(listing.getProviderId()))
                .thenReturn(Optional.of(new ProviderSummary(UUID.randomUUID(), "Name", "ACTIVE", ownerId)));

        assertThrows(AccessDeniedException.class, () -> service.create(
                new AdCampaignService.CreateAdCampaignRequest(listing.getId(), 50000L, 100L, 5L, null, null),
                authentication));
    }

    @Test
    void create_rejectsASecondLiveCampaignOnTheListing() {
        ProviderListing listing = activeListing();
        when(listingRepository.findById(listing.getId())).thenReturn(Optional.of(listing));
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(listing.getId(), AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.of(AdCampaign.start(ownerId, listing.getId(),
                        1000L, 10L, 1L, "SAR", NOW, null)));
        ownedByCaller(listing);

        assertThrows(ConflictException.class, () -> service.create(
                new AdCampaignService.CreateAdCampaignRequest(listing.getId(), 50000L, 100L, 5L, null, null),
                authentication));
    }

    @Test
    void create_rejectsAPastDurationEnd() {
        ProviderListing listing = activeListing();
        when(listingRepository.findById(listing.getId())).thenReturn(Optional.of(listing));
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(listing.getId(), AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.empty());
        ownedByCaller(listing);

        assertThrows(com.marketplace.shared.api.BadRequestException.class, () -> service.create(
                new AdCampaignService.CreateAdCampaignRequest(listing.getId(), 50000L, 100L, 5L, null, NOW.minusSeconds(1)),
                authentication));
    }

    @Test
    void pause_whenTheSettlementEndsTheCampaignReturnsTheEndedViewNotA409() {
        // CodeRabbit review round on #741's part 4 (2026-10-28, Major —
        // Functional Correctness): the settlement can consume the rest of
        // the budget and flip the campaign to ENDED inside
        // settleOpenWindow; the hold request arrived while the campaign
        // was ACTIVE, so the pre-fix post-settlement pause() failed its
        // transition guard and answered a spurious 409. The hold now only
        // fires when the campaign is STILL ACTIVE after the settlement —
        // an ended campaign already bills nothing, the hold's own goal.
        AdCampaign campaign = AdCampaign.start(ownerId, UUID.randomUUID(), 100000L, 10L, 1L, "SAR",
                NOW.minus(Duration.ofDays(5)), null);
        when(campaignRepository.findByIdAndProviderId(campaign.getId(), ownerId))
                .thenReturn(Optional.of(campaign));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);
        org.mockito.Mockito.doAnswer(inv -> {
            AdCampaign settling = inv.getArgument(0);
            // the budget's last slice — the settlement's own consume edge
            settling.consume(settling.getBudgetCents());
            return null;
        }).when(billingExecutor).settleOpenWindow(any(AdCampaign.class), any(LocalDate.class));

        AdCampaignService.AdCampaignView view = service.pause(campaign.getId(), authentication);

        assertEquals(AdCampaignStatus.ENDED, campaign.getStatus());
        assertEquals(AdCampaignStatus.ENDED.name(), view.status());
    }

    @Test
    void pause_refusesANonActiveCampaignBeforeAnySettlementRuns() {
        // The pre-settlement rejection keeps the honest 409 for every
        // non-ACTIVE campaign — and never bills a dark campaign to
        // manufacture it.
        AdCampaign campaign = AdCampaign.start(ownerId, UUID.randomUUID(), 100000L, 10L, 1L, "SAR",
                NOW.minus(Duration.ofDays(5)), null);
        campaign.pause();
        when(campaignRepository.findByIdAndProviderId(campaign.getId(), ownerId))
                .thenReturn(Optional.of(campaign));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);

        assertThrows(ConflictException.class, () -> service.pause(campaign.getId(), authentication));
        verify(billingExecutor, never()).settleOpenWindow(any(AdCampaign.class), any(LocalDate.class));
    }

    @Test
    void pause_then_resume_jumpsTheMarkerPastTheDarkDays() {
        AdCampaign campaign = AdCampaign.start(ownerId, UUID.randomUUID(), 100000L, 10L, 1L, "SAR",
                NOW.minus(Duration.ofDays(5)), null);
        when(campaignRepository.findByIdAndProviderId(campaign.getId(), ownerId))
                .thenReturn(Optional.of(campaign));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);

        service.pause(campaign.getId(), authentication);
        assertEquals(AdCampaignStatus.PAUSED, campaign.getStatus());

        // The resume day itself is the first unsettled billable date — the
        // dark days in between can never reach a charge row.
        service.resume(campaign.getId(), authentication);
        assertEquals(AdCampaignStatus.ACTIVE, campaign.getStatus());
        assertEquals(LocalDate.parse("2026-10-03"), campaign.getBilledThrough());
    }

    @Test
    void pause_settlesTheAccruedCompleteActiveDaysBeforeTheHold() {
        // Agent findings 2026-10-26T13:11 export, row 45: the owner's hold
        // must not discard the unsettled window — the settlement runs on
        // the campaign WHILE IT IS STILL ACTIVE (the billable state), with
        // the pause's own UTC today as the EXCLUSIVE horizon (the pause
        // day itself stays the one documented forfeit).
        AdCampaign campaign = AdCampaign.start(ownerId, UUID.randomUUID(), 100000L, 10L, 1L, "SAR",
                NOW.minus(Duration.ofDays(5)), null);
        when(campaignRepository.findByIdAndProviderId(campaign.getId(), ownerId))
                .thenReturn(Optional.of(campaign));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);
        java.util.List<AdCampaignStatus> statusAtSettlement = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            statusAtSettlement.add(((AdCampaign) inv.getArgument(0)).getStatus());
            return null;
        }).when(billingExecutor).settleOpenWindow(any(AdCampaign.class), any(LocalDate.class));

        service.pause(campaign.getId(), authentication);

        // The settlement ran exactly once, on the caller's own campaign,
        // at the service's UTC today — and the status it observed was
        // ACTIVE (the window was still billable, not dark).
        verify(billingExecutor).settleOpenWindow(campaign, LocalDate.parse("2026-10-03"));
        assertEquals(java.util.List.of(AdCampaignStatus.ACTIVE), statusAtSettlement);
        assertEquals(AdCampaignStatus.PAUSED, campaign.getStatus());
    }

    @Test
    void ownedReads_answer404ForAnotherProvidersCampaign() {
        UUID campaignId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(campaignRepository.findByIdAndProviderId(eq(campaignId), any(UUID.class)))
                .thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.pause(campaignId, authentication));
        assertThrows(ResourceNotFoundException.class,
                () -> service.charges(campaignId, authentication));
    }

    @Test
    void resume_rejectsACampaignWhoseDurationAlreadyEnded() {
        // CodeRabbit W5 r1, adopted: the billing run skips PAUSED campaigns,
        // so an expired-but-paused campaign would otherwise return to the
        // boost with a dead duration until the next daily run — the resume
        // gate refuses it outright.
        AdCampaign expired = AdCampaign.start(ownerId, UUID.randomUUID(), 100000L, 10L, 1L, "SAR",
                NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(1)));
        expired.pause();
        when(campaignRepository.findByIdAndProviderId(expired.getId(), ownerId))
                .thenReturn(Optional.of(expired));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);

        assertThrows(ConflictException.class, () -> service.resume(expired.getId(), authentication));
        assertEquals(AdCampaignStatus.PAUSED, expired.getStatus());
    }

    @Test
    void resume_rejectsWhenASecondLiveCampaignOwnsTheListing() {
        // CodeRabbit W5 r3, adopted: create rejects only an ACTIVE campaign,
        // so a paused campaign's listing may carry a NEWER live one — the
        // single-promotion law guards the lift with the same friendly 409
        // (the V103 partial unique index stays the concurrency backstop).
        UUID listingId = UUID.randomUUID();
        AdCampaign paused = AdCampaign.start(ownerId, listingId, 100000L, 10L, 1L, "SAR",
                NOW.minus(Duration.ofDays(5)), null);
        paused.pause();
        AdCampaign live = AdCampaign.start(ownerId, listingId, 50000L, 10L, 1L, "SAR", NOW, null);
        when(campaignRepository.findByIdAndProviderId(paused.getId(), ownerId))
                .thenReturn(Optional.of(paused));
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(listingId, AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.of(live));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);

        assertThrows(ConflictException.class, () -> service.resume(paused.getId(), authentication));
        assertEquals(AdCampaignStatus.PAUSED, paused.getStatus());
    }

    @Test
    void everyStateTransition_evictsTheCatalogOrderedPages() {
        // CodeRabbit W5 r1, adopted: the boost's truth changed at every
        // transition — the cached ordered pages must not serve yesterday's
        // order (the same AFTER_COMMIT eviction law the listing writes and
        // the ranking job apply).
        ProviderListing listing = activeListing();
        when(listingRepository.findById(listing.getId())).thenReturn(Optional.of(listing));
        when(campaignRepository.findFirstByListingIdAndStatusOrderByIdAsc(listing.getId(), AdCampaignStatus.ACTIVE))
                .thenReturn(Optional.empty());
        ownedByCaller(listing);
        saveReturnsCampaign();

        service.create(new AdCampaignService.CreateAdCampaignRequest(
                listing.getId(), 50000L, 100L, 5L, null, null), authentication);
        org.mockito.Mockito.verify(eventPublisher)
                .publishEvent(any(com.marketplace.shared.api.CacheInvalidationRequested.class));
    }
}
