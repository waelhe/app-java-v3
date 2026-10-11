package com.marketplace.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ADR-0011 (plan D-15 — DSA (EU) 2022/2065 Art. 26(1)(a)): the rows'
 * real-time promotion identification. {@code ListingSummary#promoted()}
 * must speak the SAME live-promotion law the ordering's first tier speaks
 * ({@code ProviderListingRepository}'s baked ORDER BY flag):
 * <ul>
 *   <li>a live paid campaign — ACTIVE, remaining budget, inside its
 *       duration — promotes the row (the repository's batch answer);</li>
 *   <li>a future admin featured window (promoted_until) promotes the row;</li>
 *   <li>an expired window with no live campaign does NOT — the expiry is
 *       self-correcting at read time through the injected Clock;</li>
 *   <li>the whole page costs ONE batch query — never per-row (the W3
 *       names/stats batch discipline).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ListingPromotionMarkingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ProviderListingRepository listingRepository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ProviderNameResolver providerNameResolver;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private ProviderLookupPort providerLookupPort;

    @Mock
    private CatalogProperties catalogProperties;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private com.marketplace.shared.api.ReviewStatsPort reviewStatsPort;

    @Mock
    private AdCampaignRepository adCampaignRepository;

    private CatalogService service;

    @BeforeEach
    void setUp() {
        service = new CatalogService(listingRepository, currentUserProvider,
                providerNameResolver, eventPublisher, providerLookupPort,
                CLOCK, catalogProperties, categoryRepository, reviewStatsPort,
                adCampaignRepository);
    }

    private ProviderListing listingWithWindow(Instant promotedUntil) {
        ProviderListing listing = ProviderListing.create(
                UUID.randomUUID(), "title", "desc", "cat", 1000L, "SAR");
        listing.promoteUntil(promotedUntil);
        return listing;
    }

    @Test
    void theLivePaidCampaignPromotesTheRow_theOrderingTierOwnLaw() {
        ProviderListing listing = ProviderListing.create(
                UUID.randomUUID(), "title", "desc", "cat", 1000L, "SAR");
        when(listingRepository.findAllById(any())).thenReturn(List.of(listing));
        when(providerNameResolver.resolveNames(any())).thenReturn(java.util.Map.of());
        when(reviewStatsPort.findStatsByProviderUserIds(any())).thenReturn(java.util.Map.of());
        when(adCampaignRepository.findLiveCampaignListingIds(anyCollection(),
                org.mockito.ArgumentMatchers.eq(AdCampaignStatus.ACTIVE), any()))
                .thenReturn(List.of(listing.getId()));

        List<com.marketplace.shared.api.ListingSummary> rows =
                service.findSummariesByIds(List.of(listing.getId()));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).promoted())
                .as("DSA Art. 26(1)(a): the live paid promotion is identified on the row")
                .isTrue();
        // the batch discipline: one grouped query for the whole page
        verify(adCampaignRepository).findLiveCampaignListingIds(anyCollection(),
                org.mockito.ArgumentMatchers.eq(AdCampaignStatus.ACTIVE), any());
    }

    @Test
    void theFutureFeaturedWindowPromotesTheRow_withoutAnyCampaign() {
        ProviderListing listing = listingWithWindow(Instant.parse("2026-10-12T00:00:00Z"));
        when(listingRepository.findAllById(any())).thenReturn(List.of(listing));
        when(providerNameResolver.resolveNames(any())).thenReturn(java.util.Map.of());
        when(reviewStatsPort.findStatsByProviderUserIds(any())).thenReturn(java.util.Map.of());
        when(adCampaignRepository.findLiveCampaignListingIds(anyCollection(),
                org.mockito.ArgumentMatchers.eq(AdCampaignStatus.ACTIVE), any()))
                .thenReturn(List.of());

        List<com.marketplace.shared.api.ListingSummary> rows =
                service.findSummariesByIds(List.of(listing.getId()));

        assertThat(rows.get(0).promoted()).isTrue();
    }

    @Test
    void theExpiredWindowWithNoLiveCampaignIsNotPromoted() {
        // the window ended BEFORE the read instant — the ordering's own
        // expiry-aware flag would evaluate false; the marking must agree.
        ProviderListing listing = listingWithWindow(Instant.parse("2026-10-10T00:00:00Z"));
        when(listingRepository.findAllById(any())).thenReturn(List.of(listing));
        when(providerNameResolver.resolveNames(any())).thenReturn(java.util.Map.of());
        when(reviewStatsPort.findStatsByProviderUserIds(any())).thenReturn(java.util.Map.of());
        when(adCampaignRepository.findLiveCampaignListingIds(anyCollection(),
                org.mockito.ArgumentMatchers.eq(AdCampaignStatus.ACTIVE), any()))
                .thenReturn(List.of());

        List<com.marketplace.shared.api.ListingSummary> rows =
                service.findSummariesByIds(List.of(listing.getId()));

        assertThat(rows.get(0).promoted()).isFalse();
    }

    @Test
    void theEmptyPageAsksNothingOfTheCampaignRepository() {
        assertThat(service.findSummariesByIds(List.of())).isEmpty();
        verify(adCampaignRepository, never()).findLiveCampaignListingIds(anyCollection(),
                org.mockito.ArgumentMatchers.eq(AdCampaignStatus.ACTIVE), any());
    }

    @Test
    void theNullWindowRidesTheCampaignAnswerAlone() {
        // an undeclared window (NULL promoted_until) with no live campaign:
        // the honest organic row.
        ProviderListing listing = ProviderListing.create(
                UUID.randomUUID(), "title", "desc", "cat", 1000L, "SAR");
        when(listingRepository.findAllById(any())).thenReturn(List.of(listing));
        when(providerNameResolver.resolveNames(any())).thenReturn(java.util.Map.of());
        when(reviewStatsPort.findStatsByProviderUserIds(any())).thenReturn(java.util.Map.of());
        when(adCampaignRepository.findLiveCampaignListingIds(anyCollection(),
                org.mockito.ArgumentMatchers.eq(AdCampaignStatus.ACTIVE), any()))
                .thenReturn(List.of());

        List<com.marketplace.shared.api.ListingSummary> rows =
                service.findSummariesByIds(List.of(listing.getId()));

        assertThat(rows.get(0).promoted()).isFalse();
    }
}
