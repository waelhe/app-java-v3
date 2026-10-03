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
    private final ProviderListingRepository listingRepository = mock(ProviderListingRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ProviderLookupPort providerLookupPort = mock(ProviderLookupPort.class);
    private final Authentication authentication = mock(Authentication.class);

    private static final Instant NOW = Instant.parse("2026-10-03T10:15:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final UUID ownerId = UUID.randomUUID();
    private AdCampaignService service;

    @BeforeEach
    void setUp() {
        service = new AdCampaignService(campaignRepository, chargeRepository,
                listingRepository, currentUserProvider, providerLookupPort, CLOCK);
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
        // The window identity: the marker starts at the start's own UTC day —
        // nothing before the campaign exists can ever bill.
        assertEquals(LocalDate.parse("2026-10-03"), view.billedThrough());
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
}
