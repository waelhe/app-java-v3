package com.marketplace.booking;

import com.marketplace.booking.spi.BookingStatsAdapter;
import com.marketplace.shared.api.BookingStatsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * L25 (feature-expansion roadmap §5): the booking module's stats-port
 * implementation delegates the COMPLETED-count to the derived query with
 * the exact window bounds — the status/window semantics run against real
 * PostgreSQL in {@code ProviderStatsIntegrationTest}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = { BookingStatsAdapter.class })
class BookingStatsAdapterTest {

    @Autowired
    private BookingStatsPort bookingStatsPort;

    @MockitoBean
    private BookingRepository bookingRepository;

    @Test
    void delegatesTheCompletedCount_withTheWindowVerbatim() {
        UUID providerId = UUID.randomUUID();
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-01T00:00:00Z");
        when(bookingRepository.countByProviderIdAndStatusAndStartsAtGreaterThanEqualAndStartsAtLessThan(
                eq(providerId), eq(BookingStatus.COMPLETED), eq(from), eq(to))).thenReturn(2L);

        long count = bookingStatsPort.countCompletedForProviderBetween(providerId, from, to);

        assertThat(count).isEqualTo(2L);
        verify(bookingRepository).countByProviderIdAndStatusAndStartsAtGreaterThanEqualAndStartsAtLessThan(
                same(providerId), eq(BookingStatus.COMPLETED), same(from), same(to));
        verifyNoMoreInteractions(bookingRepository);
    }
}
