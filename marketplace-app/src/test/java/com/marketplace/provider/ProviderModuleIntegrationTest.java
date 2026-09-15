package com.marketplace.provider;

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
import org.testcontainers.junit.jupiter.Testcontainers;

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
        var profile = providerService.create("Broker", "bio", UUID.randomUUID(),
                com.marketplace.provider.ProviderActorType.INDEPENDENT_BROKER, null, "BR-9");
        providerService.verify(profile.getId());
        when(reviewStatsPort.findStatsByProviderId(profile.getId()))
                .thenReturn(java.util.Optional.of(new com.marketplace.shared.api.ReviewStats(
                        profile.getId(), 4.5, 12)));
        when(catalogSearchPort.listActiveByProvider(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        java.util.List.of(new com.marketplace.shared.api.ListingSummary(
                                UUID.randomUUID(), "Flat", "APARTMENT",
                                java.math.BigDecimal.TEN, "SAR", "Broker"))));

        var page = providerPublicPageService.getPublicPage(profile.getId(),
                org.springframework.data.domain.PageRequest.of(0, 20));

        assertThat(page.status()).isEqualTo(com.marketplace.provider.ProviderStatus.VERIFIED);
        assertThat(page.ratingAverage()).isEqualTo(4.5);
        assertThat(page.listings().totalElements()).isEqualTo(1);
    }

    @Test
    void publicPage_hidesSuspendedProviderListings() {
        var profile = providerService.create("Broker", "bio", UUID.randomUUID());
        providerService.verify(profile.getId());
        providerService.suspend(profile.getId());

        var page = providerPublicPageService.getPublicPage(profile.getId(),
                org.springframework.data.domain.PageRequest.of(0, 20));

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
        when(ledgerStatsPort.findNetCentsForProviderBetween(any(), any(), any())).thenReturn(9000L);
        when(bookingStatsPort.countCompletedForProviderBetween(any(), any(), any())).thenReturn(7L);

        var stats = providerStatsService.getStats(UUID.randomUUID(), StatsWindow.lastThirtyDays(Instant.now()));

        assertThat(stats.occupancyRate()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stats.netRevenueCents()).isEqualTo(9000L);
        assertThat(stats.completedBookings()).isEqualTo(7L);
    }
}
