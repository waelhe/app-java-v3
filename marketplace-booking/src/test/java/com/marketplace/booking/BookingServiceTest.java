package com.marketplace.booking;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ListingPriceProvider.ListingInfo;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ListingPriceProvider listingPriceProvider = mock(ListingPriceProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private BookingService service;
    private final UUID consumerId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, currentUserProvider, eventPublisher, listingPriceProvider);
    }

    @Test
    void create_setsStatusToPending() {
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
        Booking booking = Booking.create(UUID.randomUUID(), providerId, UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking confirmed = service.confirm(id, authentication);

        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
    }

    @Test
    void complete_changesStatusFromConfirmedToCompleted() {
        UUID id = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), providerId, UUID.randomUUID(), 5000L, "notes");
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
        Booking booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 5000L, "notes");
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
    void getByIdForUser_returnsBookingForParticipant() {
        UUID id = UUID.randomUUID();
        Booking booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        Booking result = service.getByIdForUser(id, authentication);

        assertNotNull(result);
    }

    @Test
    void getByIdForUser_throwsWhenNotParticipant() {
        UUID id = UUID.randomUUID();
        Booking booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.getByIdForUser(id, authentication));
    }

    @Test
    void listByConsumer_returnsBookings() {
        var pageable = PageRequest.of(0, 10);
        var booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findByConsumerId(consumerId, pageable)).thenReturn(new PageImpl<>(List.of(booking)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        Page<Booking> result = service.listByConsumer(consumerId, pageable, authentication);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void listByConsumer_throwsWhenNotOwner() {
        var pageable = PageRequest.of(0, 10);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.listByConsumer(consumerId, pageable, authentication));
    }

    @Test
    void listByProvider_returnsBookings() {
        var pageable = PageRequest.of(0, 10);
        var booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findByProviderId(providerId, pageable)).thenReturn(new PageImpl<>(List.of(booking)));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);

        Page<Booking> result = service.listByProvider(providerId, pageable, authentication);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void listByProvider_throwsWhenNotOwner() {
        var pageable = PageRequest.of(0, 10);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class,
                () -> service.listByProvider(providerId, pageable, authentication));
    }

    @Test
    void listAll_returnsAllBookings() {
        var pageable = PageRequest.of(0, 10);
        var booking = Booking.create(consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(booking)));

        Page<Booking> result = service.listAll(pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void listByStatus_returnsFilteredBookings() {
        var pageable = PageRequest.of(0, 10);
        when(bookingRepository.findByStatus(BookingStatus.PENDING, pageable)).thenReturn(new PageImpl<>(List.of()));

        Page<Booking> result = service.listByStatus(BookingStatus.PENDING, pageable);

        assertNotNull(result);
    }

    @Test
    void listAllSummaries_mapsToSummaries() {
        var pageable = PageRequest.of(0, 10);
        var booking = new Booking(UUID.randomUUID(), consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(booking)));

        var result = service.listAllSummaries(pageable);

        assertEquals(1, result.getContent().size());
        assertEquals(consumerId, result.getContent().getFirst().consumerId());
    }

    @Test
    void listByStatusSummary_withEnum_returnsSummaries() {
        var pageable = PageRequest.of(0, 10);
        var booking = new Booking(UUID.randomUUID(), consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findByStatus(BookingStatus.CONFIRMED, pageable)).thenReturn(new PageImpl<>(List.of(booking)));

        var result = service.listByStatusSummary(BookingStatus.CONFIRMED, pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void listByStatusSummary_withString_returnsSummaries() {
        var pageable = PageRequest.of(0, 10);
        var booking = new Booking(UUID.randomUUID(), consumerId, providerId, UUID.randomUUID(), 5000L, null);
        when(bookingRepository.findByStatus(BookingStatus.PENDING, pageable)).thenReturn(new PageImpl<>(List.of(booking)));

        var result = service.listByStatusSummary("PENDING", pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void listByStatusSummary_withInvalidString_throws() {
        var pageable = PageRequest.of(0, 10);
        assertThrows(IllegalArgumentException.class,
                () -> service.listByStatusSummary("INVALID", pageable));
    }

    @Test
    void confirm_throwsWhenNotProvider() {
        UUID id = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), providerId, UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.confirm(id, authentication));
    }

    @Test
    void cancel_throwsWhenNotParticipant() {
        UUID id = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.cancel(id, authentication));
    }

    @Test
    void confirm_allowsAdmin() {
        UUID id = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), providerId, UUID.randomUUID(), 5000L, "notes");
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);

        service.confirm(id, authentication);

        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    @Test
    void listByConsumer_allowsAdmin() {
        var pageable = PageRequest.of(0, 10);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(bookingRepository.findByConsumerId(consumerId, pageable)).thenReturn(new PageImpl<>(List.of()));

        service.listByConsumer(consumerId, pageable, authentication);

        verify(bookingRepository).findByConsumerId(consumerId, pageable);
    }
}
