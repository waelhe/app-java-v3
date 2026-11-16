package com.marketplace.booking.spi;

import com.marketplace.booking.BookingRepository;
import com.marketplace.booking.BookingStatus;
import com.marketplace.shared.api.BookingStatsPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): the booking module's implementation of
 * the {@link BookingStatsPort} cross-module contract (the
 * {@code ReviewStatsAdapter} house pattern). A read-only delegation to the
 * derived count query — COMPLETED bookings starting inside the window.
 */
@Component
@Transactional(readOnly = true)
public class BookingStatsAdapter implements BookingStatsPort {

    private final BookingRepository bookingRepository;

    public BookingStatsAdapter(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public long countCompletedForProviderBetween(UUID providerId, Instant from, Instant to) {
        return bookingRepository.countByProviderIdAndStatusAndStartsAtGreaterThanEqualAndStartsAtLessThan(
                providerId, BookingStatus.COMPLETED, from, to);
    }
}
