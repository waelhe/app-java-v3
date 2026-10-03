package com.marketplace.provider;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.ReviewStatsPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class ProviderModuleIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    // L21: the review-stats listener resolves aggregates through the
    // cross-module port — outside this module slice, so the standard
    // @MockitoBean pattern applies (house convention).
    @MockitoBean
    ReviewStatsPort reviewStatsPort;

    // L25: the stats service aggregates through three more cross-module
    // read ports — mocked at the slice boundary exactly like the review
    // port (the real adapters live in their own modules and join in the
    // full-context integration test).
    @MockitoBean
    com.marketplace.shared.api.AvailabilityLookupPort availabilityLookupPort;

    @MockitoBean
    com.marketplace.shared.api.LedgerStatsPort ledgerStatsPort;

    @MockitoBean
    com.marketplace.shared.api.BookingStatsPort bookingStatsPort;

    // L36: the public page composes through the catalog search port —
    // outside this module slice, same @MockitoBean convention.
    @MockitoBean
    com.marketplace.shared.api.CatalogSearchPort catalogSearchPort;

    // L40: the listing-views analytics read — outside this module slice
    // (catalog owns the daily buckets), same @MockitoBean convention as
    // every cross-module port above (the L38 GeoLookupPort lesson: a port
    // without a slice bean fails the whole context boot).
    @MockitoBean
    com.marketplace.shared.api.ListingViewsStatsPort listingViewsStatsPort;

    // W1 (greptile W1 r7, adopted from the root): ProviderPublicPageService
    // resolves the reviews mode through the settings port — outside this
    // module slice (the adapter lives in the app's admin surface), so the
    // same @MockitoBean convention applies. Without it the expanded
    // constructor fails the whole context boot.
    @MockitoBean
    com.marketplace.shared.api.SystemSettingsPort systemSettingsPort;

    // W1 (the frontend-consumer extension, D-W1-5): the public page now
    // composes the reviews block through the PublishedReviewsPort — the
    // adapter lives in the reviews module, outside this provider slice,
    // so the same @MockitoBean convention applies (the L38 GeoLookupPort
    // lesson verbatim: a port without a slice bean fails the whole
    // context boot).
    @MockitoBean
    com.marketplace.shared.api.PublishedReviewsPort publishedReviewsPort;

    // W2 (the business page's areas, G13): the public page resolves the
    // declared areas' names through the geo module's cached tree — the
    // adapter lives in the geo module, outside this provider slice, so
    // the same @MockitoBean convention applies (the L38 lesson again:
    // ProviderPublicPageService's expanded constructor fails the whole
    // context boot without a slice bean — the CI-measured first run of
    // this wave).
    @MockitoBean
    com.marketplace.shared.api.GeoLookupPort geoLookupPort;

    // The CI-measured first-run lesson: a Mockito mock answers the port's
    // default methods with null/0 — and ReviewMode.parse(null) fails loud
    // ("carries no value") instead of falling back to the default the real
    // adapter would return. The tests below assert the seed mode's behavior
    // (VERIFIED_ONLY — V84's seeded value), so the stub states exactly the
    // world they run under.
    @org.junit.jupiter.api.BeforeEach
    void stubTheReviewsMode() {
        org.mockito.Mockito.when(systemSettingsPort.getStringOrDefault(
                        org.mockito.ArgumentMatchers.eq(com.marketplace.shared.api.SystemSettingKeys.REVIEWS_MODE),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.marketplace.shared.api.ReviewMode.VERIFIED_ONLY.name());
        // The reviews block's port answers the honest empty page — the
        // port's own contract (never null; the slice has no reviews data
        // by construction).
        org.mockito.Mockito.when(publishedReviewsPort.findPublishedByProviderUserId(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(com.marketplace.shared.api.PagedRequest.class)))
                .thenAnswer(invocation -> com.marketplace.shared.api.PagedResponse
                        .empty(invocation.getArgument(1, com.marketplace.shared.api.PagedRequest.class)));
        // The geo tree's neutral stub — the same honest-empty posture: the
        // slice declares no areas, so the composition never asks for a
        // name; should it ever ask, the answer is an empty root (a null
        // tree would NPE the flatten — the mock's default answer).
        org.mockito.Mockito.when(geoLookupPort.getTree()).thenReturn(new com.marketplace.shared.api.GeoLookupPort.GeoNode(
                java.util.UUID.randomUUID(), null, 0, "الجذر", "root", "root", java.util.List.of()));
    }

    @Autowired
    private ProviderService providerService;

    @Autowired
    private ProviderStatsService providerStatsService;

    @Autowired
    private ProviderPublicPageService providerPublicPageService;

    @Test
    void contextLoads() {
    }

    @Test
    void createProvider_persists() {
        var profile = providerService.create("Test Provider", "A test provider", UUID.randomUUID(),
                com.marketplace.provider.ProviderActorType.AGENCY, "Qudsia Prime", "BR-1");
        assertThat(profile.getId()).isNotNull();
        assertThat(profile.getDisplayName()).isEqualTo("Test Provider");
        assertThat(profile.getActorType()).isEqualTo(com.marketplace.provider.ProviderActorType.AGENCY);
        assertThat(profile.getAgencyName()).isEqualTo("Qudsia Prime");
    }

    @Test
    void publicPage_servesVerifiedProviderListingsThroughThePort() {
        // L36 through the module slice: the ports are the slice boundary;
        // the VERIFIED gate and the composite assembly are the module's own.
        UUID ownerUserId = UUID.randomUUID();
        var profile = providerService.create("Broker", "bio", ownerUserId,
                com.marketplace.provider.ProviderActorType.INDEPENDENT_BROKER, null, "BR-9");
        providerService.verify(profile.getId());
        // The rating aggregates in the reviews' id space (users.id — the
        // V6 FK's space; the profile id never reaches the reviews port).
        when(reviewStatsPort.findStatsByProviderId(ownerUserId))
                .thenReturn(java.util.Optional.of(new com.marketplace.shared.api.ReviewStats(
                        ownerUserId, 4.5, 12)));
        // W2's histogram pair (the CI-measured stubbing gap of this round):
        // the adapter's contract is "always an instance — empty buckets when
        // unrated" (RatingDistribution.of over the SQL projection), so the
        // slice stub answers the same honest shape.
        when(reviewStatsPort.findRatingDistributionByProviderId(ownerUserId))
                .thenReturn(com.marketplace.shared.api.RatingDistribution.of(ownerUserId,
                        java.util.List.of()));
        when(reviewStatsPort.findGeneralRatingDistributionByProviderId(ownerUserId))
                .thenReturn(com.marketplace.shared.api.RatingDistribution.of(ownerUserId,
                        java.util.List.of()));
        when(catalogSearchPort.listActiveByProvider(any(), any()))
                .thenReturn(com.marketplace.shared.api.PagedResponse.of(
                        new org.springframework.data.domain.PageImpl<>(
                                java.util.List.of(new com.marketplace.shared.api.ListingSummary(
                                        UUID.randomUUID(), "Flat", "APARTMENT",
                                        java.math.BigDecimal.TEN, "SAR", "Broker", null, 0L)))));

        var page = providerPublicPageService.getPublicPage(profile.getId(),
                org.springframework.data.domain.PageRequest.of(0, 20),
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.status()).isEqualTo(com.marketplace.provider.ProviderStatus.VERIFIED);
        assertThat(page.ratingAverage()).isEqualTo(4.5);
        assertThat(page.listings().totalElements()).isEqualTo(1);
    }

    @Test
    void publicPage_hidesSuspendedProviderListings() {
        var profile = providerService.create("Broker", "bio", UUID.randomUUID());
        providerService.verify(profile.getId());
        providerService.suspend(profile.getId());

        // The same histogram pair stub (the unrated profile's honest empty
        // buckets — the adapter's own contract, the CI-measured gap).
        when(reviewStatsPort.findRatingDistributionByProviderId(any()))
                .thenReturn(com.marketplace.shared.api.RatingDistribution.of(
                        UUID.randomUUID(), java.util.List.of()));
        when(reviewStatsPort.findGeneralRatingDistributionByProviderId(any()))
                .thenReturn(com.marketplace.shared.api.RatingDistribution.of(
                        UUID.randomUUID(), java.util.List.of()));

        var page = providerPublicPageService.getPublicPage(profile.getId(),
                org.springframework.data.domain.PageRequest.of(0, 20),
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.status()).isEqualTo(com.marketplace.provider.ProviderStatus.SUSPENDED);
        assertThat(page.listings().totalElements()).isZero();
        verify(catalogSearchPort, never()).listActiveByProvider(any(), any());
    }

    @Test
    void getById_throwsForUnknown() {
        assertThrows(ResourceNotFoundException.class, () -> providerService.getById(UUID.randomUUID()));
    }

    @Test
    void statsWindow_defaultsToThirtyDays_andAggregatesThroughThePorts() {
        // The default window (acceptance criterion 3) through the module
        // slice: the ports are the slice boundary; the window arithmetic
        // is the module's own.
        when(availabilityLookupPort.findProviderSlotStats(any(), any(), any()))
                .thenReturn(new com.marketplace.shared.api.SlotWindowStats(10, 4));
        when(ledgerStatsPort.findNetByCurrencyForProviderBetween(any(), any(), any()))
                .thenReturn(java.util.List.of(new com.marketplace.shared.api.CurrencyAmount("SAR", 9000L)));
        when(bookingStatsPort.countCompletedForProviderBetween(any(), any(), any())).thenReturn(7L);

        var stats = providerStatsService.getStats(UUID.randomUUID(), StatsWindow.lastThirtyDays(Instant.now()));

        assertThat(stats.occupancyRate()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.netRevenue())
                .containsExactly(new com.marketplace.shared.api.CurrencyAmount("SAR", 9000L));
        assertThat(stats.completedBookings()).isEqualTo(7L);
    }
}
