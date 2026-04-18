package com.marketplace.booking;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adapter that implements the {@link BookingParticipantProvider} port
 * using the booking module's own repository.
 *
 * <p>This allows other modules (e.g., reviews) to look up booking
 * participants without depending on the booking module directly.
 * The port is a synchronous interface (not an event) because the
 * caller needs the provider ID before persisting — see
 * {@link BookingParticipantProvider} for the design rationale.</p>
 */
@Component
public class BookingParticipantProviderAdapter implements BookingParticipantProvider {

    private final BookingRepository bookingRepository;

    public BookingParticipantProviderAdapter(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Override
    public UUID getProviderId(UUID bookingId) {
        return bookingRepository.findById(bookingId)
                .map(Booking::getProviderId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
    }
}
