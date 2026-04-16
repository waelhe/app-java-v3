package com.marketplace.booking;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;

    public BookingService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    @Transactional(readOnly = true)
    public Booking getById(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Booking> listByConsumer(UUID consumerId, Pageable pageable) {
        return bookingRepository.findByConsumerId(consumerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Booking> listByProvider(UUID providerId, Pageable pageable) {
        return bookingRepository.findByProviderId(providerId, pageable);
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public Booking create(UUID consumerId, UUID providerId, UUID listingId,
                           Long priceCents, String notes) {
        Booking booking = Booking.create(consumerId, providerId, listingId, priceCents, notes);
        return bookingRepository.save(booking);
    }

    @PreAuthorize("hasRole('PROVIDER')")
    @Retry(name = "booking")
    public Booking confirm(UUID id) {
        Booking booking = getById(id);
        booking.confirm();
        return booking;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    @Retry(name = "booking")
    public Booking complete(UUID id) {
        Booking booking = getById(id);
        booking.complete();
        return booking;
    }

    @PreAuthorize("hasAnyRole('CONSUMER','PROVIDER')")
    public Booking cancel(UUID id) {
        Booking booking = getById(id);
        booking.cancel();
        return booking;
    }
}