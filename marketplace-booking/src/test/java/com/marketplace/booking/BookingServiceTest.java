package com.marketplace.booking;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ListingPriceProvider.ListingInfo;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ListingPriceProvider listingPriceProvider = mock(ListingPriceProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, currentUserProvider, eventPublisher, listingPriceProvider);
    }

    @Test
    void create_setsStatusToPending() {
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();

        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingInfo(providerId, 5000L));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Booking booking = service.create(consumerId, listingId, "test notes");

        assertEquals(BookingStatus.PENDING, booking.getStatus());
        assertEquals(consumerId, booking.getConsumerId());
        assertEquals(providerId, booking.getProviderId());
        assertEquals(5000L, booking.getPriceCents());
    }

    @Test
    void confirm_changesStatusFromPendingToConfirmed() {
        UUID id = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), providerId,
                UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking confirmed = service.confirm(id, authentication);

        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
    }

    @Test
    void complete_changesStatusFromConfirmedToCompleted() {
        UUID id = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), providerId,
                UUID.randomUUID(), 5000L, "notes");
        booking.confirm();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking completed = service.complete(id, authentication);

        assertEquals(BookingStatus.COMPLETED, completed.getStatus());
    }

    @Test
    void cancel_changesStatusToCancelled() {
        UUID id = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Booking booking = Booking.create(consumerId, providerId,
                UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking cancelled = service.cancel(id, authentication);

        assertEquals(BookingStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void getById_throwsResourceNotFoundException() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getById(id));
    }

    @Test
    void confirm_throwsWhenNotProvider() {
        UUID id = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), providerId,
                UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.confirm(id, authentication));
    }

    @Test
    void cancel_throwsWhenNotParticipant() {
        UUID id = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.cancel(id, authentication));
    }
}
