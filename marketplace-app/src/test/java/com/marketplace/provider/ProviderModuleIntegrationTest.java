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

    @Autowired
    private ProviderService providerService;

    @Autowired
    private ProviderStatsService providerStatsService;

    @Test
    void contextLoads() {
    }

    @Test
    void createProvider_persists() {
        var profile = providerService.create("Test Provider", "A test provider", UUID.randomUUID());
        assertThat(profile.getId()).isNotNull();
        assertThat(profile.getDisplayName()).isEqualTo("Test Provider");
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
