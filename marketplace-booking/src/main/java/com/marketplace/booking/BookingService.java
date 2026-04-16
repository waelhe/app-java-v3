package com.marketplace.booking;

import com.marketplace.shared.api.BookingSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
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
    private final CurrentUserProvider currentUserProvider;

    public BookingService(BookingRepository bookingRepository, CurrentUserProvider currentUserProvider) {
        this.bookingRepository = bookingRepository;
        this.currentUserProvider = currentUserProvider;
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

    @Transactional(readOnly = true)
    public Page<Booking> listByStatus(BookingStatus status, Pageable pageable) {
        return bookingRepository.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Booking> listAll(Pageable pageable) {
        return bookingRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<BookingSummary> listAllSummaries(Pageable pageable) {
        return listAll(pageable).map(this::toBookingSummary);
    }

    @Transactional(readOnly = true)
    public Page<BookingSummary> listByStatusSummary(BookingStatus status, Pageable pageable) {
        return listByStatus(status, pageable).map(this::toBookingSummary);
    }

    /**
     * Accepts a status string from the admin API, parses it to BookingStatus,
     * and returns a page of BookingSummary.
     */
    @Transactional(readOnly = true)
    public Page<BookingSummary> listByStatusSummary(String status, Pageable pageable) {
        BookingStatus bookingStatus = BookingStatus.valueOf(status.toUpperCase());
        return listByStatusSummary(bookingStatus, pageable);
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
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!booking.getProviderId().equals(currentUserId) && !currentUserProvider.isAdmin(authentication)) {
            throw new IllegalArgumentException("You are not the provider for this booking");
        }
    }

    private void verifyParticipantOwnership(Booking booking, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!booking.getConsumerId().equals(currentUserId)
                && !booking.getProviderId().equals(currentUserId)
                && !currentUserProvider.isAdmin(authentication)) {
            throw new IllegalArgumentException("You are not a participant in this booking");
        }
    }

    private BookingSummary toBookingSummary(Booking booking) {
        return new BookingSummary(
                booking.getId(),
                booking.getConsumerId(),
                booking.getProviderId(),
                booking.getListingId(),
                booking.getStatus().name(),
                booking.getPriceCents(),
                booking.getCurrency(),
                booking.getCreatedAt(),
                booking.getUpdatedAt()
        );
    }
}
