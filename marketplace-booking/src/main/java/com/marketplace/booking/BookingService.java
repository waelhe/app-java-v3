package com.marketplace.booking;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.shared.api.BookingSummary;
import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ListingPriceProvider.ListingInfo;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class BookingService implements BookingSpi {

    private final BookingRepository bookingRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ListingPriceProvider listingPriceProvider;

    public BookingService(BookingRepository bookingRepository,
                          CurrentUserProvider currentUserProvider,
                          ApplicationEventPublisher eventPublisher,
                          ListingPriceProvider listingPriceProvider) {
        this.bookingRepository = bookingRepository;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
        this.listingPriceProvider = listingPriceProvider;
    }

    @Transactional(readOnly = true)
    public Booking getById(UUID id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", id));
    }

    @Transactional(readOnly = true)
    public Booking getByIdForUser(UUID id, Authentication authentication) {
        Booking booking = getById(id);
        verifyParticipantOwnership(booking, authentication);
        return booking;
    }

    @Transactional(readOnly = true)
    public Page<Booking> listByConsumer(UUID consumerId, Pageable pageable, Authentication authentication) {
        verifyUserOrAdmin(consumerId, authentication);
        return bookingRepository.findByConsumerId(consumerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Booking> listByProvider(UUID providerId, Pageable pageable, Authentication authentication) {
        verifyUserOrAdmin(providerId, authentication);
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
     * and returns a page of BookingSummary. Wraps IllegalArgumentException
     * to prevent leaking internal enum/package details.
     */
    @Transactional(readOnly = true)
    public Page<BookingSummary> listByStatusSummary(String status, Pageable pageable) {
        try {
            BookingStatus bookingStatus = BookingStatus.valueOf(status.toUpperCase());
            return listByStatusSummary(bookingStatus, pageable);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid booking status: " + status);
        }
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public Booking create(UUID consumerId, UUID listingId, String notes) {
        ListingInfo info = listingPriceProvider.getListingInfo(listingId);
        Booking booking = Booking.create(consumerId, info.providerId(), listingId, info.priceCents(), notes);
        Booking saved = bookingRepository.save(booking);
        eventPublisher.publishEvent(new BookingCreatedEvent(saved.getId()));
        return saved;
    }

    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    @Retry(name = "booking")
    public Booking confirm(UUID id, Authentication authentication) {
        Booking booking = getById(id);
        verifyProviderOwnership(booking, authentication);
        booking.confirm();
        return booking;
    }

    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
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
            throw new AccessDeniedException("You are not the provider for this booking");
        }
    }

    private void verifyParticipantOwnership(Booking booking, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!booking.getConsumerId().equals(currentUserId)
                && !booking.getProviderId().equals(currentUserId)
                && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("You are not a participant in this booking");
        }
    }

    private void verifyUserOrAdmin(UUID userId, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!currentUserId.equals(userId) && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("You can only access your own bookings");
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
