package com.marketplace.booking.spi;

import com.marketplace.booking.Booking;
import com.marketplace.booking.BookingRepository;
import com.marketplace.shared.api.BookingExportEntry;
import com.marketplace.shared.api.BookingExportPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the booking module's implementation of the
 * {@link BookingExportPort} cross-module contract (the
 * {@code BookingStatsAdapter} house pattern). A read-only delegation to
 * the repository with the plan's provenance mapping: the first-party
 * bookings (consumer or provider side), the counterparty as an opaque UUID,
 * and the plan's fixed enumeration (status/dates/amounts) — the free-text
 * {@code notes} stay out (gate b-3's declared residual).
 */
@Component
@Transactional(readOnly = true)
public class BookingExportAdapter implements BookingExportPort {

    private final BookingRepository bookingRepository;

    public BookingExportAdapter(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public List<BookingExportEntry> exportForParticipant(UUID userId) {
        return bookingRepository
                .findAllByConsumerIdOrProviderIdOrderByCreatedAtAsc(userId, userId)
                .stream()
                .map(booking -> toEntry(booking, userId))
                .toList();
    }

    private static BookingExportEntry toEntry(Booking booking, UUID userId) {
        boolean consumerSide = userId.equals(booking.getConsumerId());
        UUID counterpartyId = consumerSide ? booking.getProviderId() : booking.getConsumerId();
        return new BookingExportEntry(
                booking.getId(),
                consumerSide ? "CONSUMER" : "PROVIDER",
                counterpartyId,
                booking.getListingId(),
                booking.getStatus().name(),
                booking.getStartsAt(),
                booking.getEndsAt(),
                booking.getPriceCents(),
                booking.getCurrency(),
                booking.getCreatedAt(),
                booking.getUpdatedAt());
    }
}
