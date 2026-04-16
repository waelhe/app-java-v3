package com.marketplace.booking;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class BookingService {

    private final BookingRepository bookingRepository;
    private final SecurityUtils securityUtils;

    public BookingService(BookingRepository bookingRepository, SecurityUtils securityUtils) {
        this.bookingRepository = bookingRepository;
        this.securityUtils = securityUtils;
    }

    @Transactional(readOnly = true)
    public Booking getById(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", id));
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
    public Booking confirm(UUID id, Authentication authentication) {
        Booking booking = getById(id);
        verifyProviderOwnership(booking, authentication);
        booking.confirm();
        return booking;
    }

    @PreAuthorize("hasRole('PROVIDER')")
    @Retry(name = "booking")
    public Booking complete(UUID id, Authentication authentication) {
        Booking booking = getById(id);
        verifyProviderOwnership(booking, authentication);
        booking.complete();
        return booking;
    }

    @PreAuthorize("hasAnyRole('CONSUMER','PROVIDER')")
    public Booking cancel(UUID id, Authentication authentication) {
        Booking booking = getById(id);
        verifyParticipantOwnership(booking, authentication);
        booking.cancel();
        return booking;
    }

    private void verifyProviderOwnership(Booking booking, Authentication authentication) {
        UUID currentUserId = securityUtils.getCurrentUserId(authentication);
        if (!booking.getProviderId().equals(currentUserId) && !securityUtils.isAdmin(authentication)) {
            throw new IllegalArgumentException("You are not the provider for this booking");
        }
    }

    private void verifyParticipantOwnership(Booking booking, Authentication authentication) {
        UUID currentUserId = securityUtils.getCurrentUserId(authentication);
        if (!booking.getConsumerId().equals(currentUserId)
                && !booking.getProviderId().equals(currentUserId)
                && !securityUtils.isAdmin(authentication)) {
            throw new IllegalArgumentException("You are not a participant in this booking");
        }
    }
}