package com.marketplace.provider;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.BookingStatsPort;
import com.marketplace.shared.api.LedgerStatsPort;
import com.marketplace.shared.api.SlotWindowStats;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * L25 (feature-expansion roadmap §5, acceptance criterion 1): the three
 * aggregates over known port answers — the manual calculation is written
 * here and the assertion compares against it:
 * occupancy = bookedSlots / totalSlots; net revenue = the ledger's signed
 * window sum; completed bookings = the booking count. The domain rules
 * (which slots count, which entries net how) live behind the ports — the
 * integration test pins those against a seeded database.
 */
class ProviderStatsServiceTest {

    private final AvailabilityLookupPort availabilityLookupPort =
            org.mockito.Mockito.mock(AvailabilityLookupPort.class);
    private final LedgerStatsPort ledgerStatsPort =
            org.mockito.Mockito.mock(LedgerStatsPort.class);
    private final BookingStatsPort bookingStatsPort =
            org.mockito.Mockito.mock(BookingStatsPort.class);

    private final ProviderStatsService service =
            new ProviderStatsService(availabilityLookupPort, ledgerStatsPort, bookingStatsPort);

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");
    private static final StatsWindow WINDOW = new StatsWindow(FROM, TO);
    private static final UUID PROVIDER_ID = UUID.randomUUID();

    @Test
    void threeAggregates_matchTheManualCalculation() {
        // Manual calculation, written down (acceptance criterion 1):
        //   slots in window: 5, of which booked: 2  -> occupancy = 2/5 = 0.4
        //   ledger net (post-commission, post-refund): 4500 cents
        //   completed bookings in window: 3
        when(availabilityLookupPort.findProviderSlotStats(PROVIDER_ID, FROM, TO))
                .thenReturn(new SlotWindowStats(5, 2));
        when(ledgerStatsPort.findNetCentsForProviderBetween(PROVIDER_ID, FROM, TO))
                .thenReturn(4500L);
        when(bookingStatsPort.countCompletedForProviderBetween(PROVIDER_ID, FROM, TO))
                .thenReturn(3L);

        ProviderStatsResponse stats = service.getStats(PROVIDER_ID, WINDOW);

        assertThat(stats.occupancyRate()).isCloseTo(2.0 / 5.0, within(1e-9));
        assertThat(stats.netRevenueCents()).isEqualTo(4500L);
        assertThat(stats.completedBookings()).isEqualTo(3L);
        assertThat(stats.from()).isEqualTo(FROM);
        assertThat(stats.to()).isEqualTo(TO);

        verify(availabilityLookupPort).findProviderSlotStats(PROVIDER_ID, FROM, TO);
        verify(ledgerStatsPort).findNetCentsForProviderBetween(PROVIDER_ID, FROM, TO);
        verify(bookingStatsPort).countCompletedForProviderBetween(PROVIDER_ID, FROM, TO);
        verifyNoMoreInteractions(availabilityLookupPort, ledgerStatsPort, bookingStatsPort);
    }

    @Test
    void providerWithoutSlots_reportsZeroOccupancy_neverNaN() {
        when(availabilityLookupPort.findProviderSlotStats(PROVIDER_ID, FROM, TO))
                .thenReturn(new SlotWindowStats(0, 0));
        when(ledgerStatsPort.findNetCentsForProviderBetween(PROVIDER_ID, FROM, TO))
                .thenReturn(0L);
        when(bookingStatsPort.countCompletedForProviderBetween(PROVIDER_ID, FROM, TO))
                .thenReturn(0L);

        ProviderStatsResponse stats = service.getStats(PROVIDER_ID, WINDOW);

        // 0 slots is a legitimate answer (a new provider), not a division
        // error: the ratio is 0.0, the response is honest.
        assertThat(stats.occupancyRate()).isZero();
        assertThat(stats.netRevenueCents()).isZero();
        assertThat(stats.completedBookings()).isZero();
    }

    @Test
    void windowBounds_reachEveryPort_verbatim() {
        // All three aggregates must measure the SAME [from, to) span — the
        // house exclusive-end convention carried by the window record.
        when(availabilityLookupPort.findProviderSlotStats(PROVIDER_ID, FROM, TO))
                .thenReturn(new SlotWindowStats(0, 0));
        when(ledgerStatsPort.findNetCentsForProviderBetween(PROVIDER_ID, FROM, TO)).thenReturn(0L);
        when(bookingStatsPort.countCompletedForProviderBetween(PROVIDER_ID, FROM, TO)).thenReturn(0L);

        service.getStats(PROVIDER_ID, WINDOW);

        verify(availabilityLookupPort).findProviderSlotStats(PROVIDER_ID, FROM, TO);
        verify(ledgerStatsPort).findNetCentsForProviderBetween(PROVIDER_ID, FROM, TO);
        verify(bookingStatsPort).countCompletedForProviderBetween(PROVIDER_ID, FROM, TO);
    }

    @Test
    void defaultWindowLength_isThirtyDays() {
        // The roadmap's default (acceptance criterion 3) — the factory the
        // controller uses when both bounds are omitted.
        StatsWindow window = StatsWindow.lastThirtyDays(Instant.now());

        assertThat(Duration.between(window.from(), window.to()))
                .isEqualTo(Duration.ofDays(30));
    }
}
