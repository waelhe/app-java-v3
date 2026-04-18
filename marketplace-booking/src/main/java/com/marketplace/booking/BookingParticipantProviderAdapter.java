package com.marketplace.booking;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adapter that implements the BookingParticipantProvider port
 * using the booking module's own repository.
 * This allows other modules (e.g., reviews) to look up booking
 * participants without depending on the booking module directly.
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
