package com.marketplace.booking;

import com.marketplace.shared.api.BookingSummary;
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

import org.instancio.Instancio;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Select.field;

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
        UUID consumerId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        UUID listingId = Instancio.create(UUID.class);

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
        UUID id = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getProviderId), providerId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking confirmed = service.confirm(id, authentication);

        assertEquals(BookingStatus.CONFIRMED, confirmed.getStatus());
    }

    @Test
    void complete_changesStatusFromConfirmedToCompleted() {
        UUID id = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getProviderId), providerId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        booking.confirm();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking completed = service.complete(id, authentication);

        assertEquals(BookingStatus.COMPLETED, completed.getStatus());
    }

    @Test
    void cancel_changesStatusToCancelled() {
        UUID id = Instancio.create(UUID.class);
        UUID consumerId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getConsumerId), consumerId)
                .set(field(Booking::getProviderId), providerId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking cancelled = service.cancel(id, authentication);

        assertEquals(BookingStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void getById_throwsResourceNotFoundException() {
        UUID id = Instancio.create(UUID.class);
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getById(id));
    }

    @Test
    void confirm_throwsWhenNotProvider() {
        UUID id = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getProviderId), providerId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.confirm(id, authentication));
    }

    @Test
    void cancel_throwsWhenNotParticipant() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.cancel(id, authentication));
    }

    @Test
    void getById_returnsBooking() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        assertEquals(booking, service.getById(id));
    }

    @Test
    void getByIdForUser_returnsBooking() {
        UUID id = Instancio.create(UUID.class);
        UUID consumerId = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getConsumerId), consumerId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        assertEquals(booking, service.getByIdForUser(id, authentication));
    }

    @Test
    void getByIdForUser_throwsWhenNotParticipant() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        assertThrows(AccessDeniedException.class, () -> service.getByIdForUser(id, authentication));
    }

    @Test
    void listByConsumer_returnsBookings() {
        UUID consumerId = Instancio.create(UUID.class);
        var pageable = PageRequest.of(0, 10);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getConsumerId), consumerId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(bookingRepository.findByConsumerId(consumerId, pageable))
                .thenReturn(new PageImpl<>(List.of(booking)));
        Page<Booking> result = service.listByConsumer(consumerId, pageable, authentication);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void listByConsumer_throwsWhenNotOwner() {
        UUID consumerId = Instancio.create(UUID.class);
        var pageable = PageRequest.of(0, 10);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        assertThrows(AccessDeniedException.class,
                () -> service.listByConsumer(consumerId, pageable, authentication));
    }

    @Test
    void listByProvider_returnsBookings() {
        UUID providerId = Instancio.create(UUID.class);
        var pageable = PageRequest.of(0, 10);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getProviderId), providerId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(bookingRepository.findByProviderId(providerId, pageable))
                .thenReturn(new PageImpl<>(List.of(booking)));
        Page<Booking> result = service.listByProvider(providerId, pageable, authentication);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void listByProvider_throwsWhenNotOwner() {
        UUID providerId = Instancio.create(UUID.class);
        var pageable = PageRequest.of(0, 10);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        assertThrows(AccessDeniedException.class,
                () -> service.listByProvider(providerId, pageable, authentication));
    }

    @Test
    void listByStatus_returnsFiltered() {
        var pageable = PageRequest.of(0, 10);
        when(bookingRepository.findByStatus(BookingStatus.PENDING, pageable))
                .thenReturn(Page.empty());
        assertNotNull(service.listByStatus(BookingStatus.PENDING, pageable));
    }

    @Test
    void listAll_returnsAll() {
        var pageable = PageRequest.of(0, 10);
        when(bookingRepository.findAll(pageable)).thenReturn(Page.empty());
        assertNotNull(service.listAll(pageable));
    }

    @Test
    void listAllSummaries_returnsSummaries() {
        var pageable = PageRequest.of(0, 10);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(booking)));
        Page<BookingSummary> result = service.listAllSummaries(pageable);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void listByStatusSummary_withString_parsesStatus() {
        var pageable = PageRequest.of(0, 10);
        when(bookingRepository.findByStatus(BookingStatus.PENDING, pageable))
                .thenReturn(Page.empty());
        assertNotNull(service.listByStatusSummary("PENDING", pageable));
    }

    @Test
    void listByStatusSummary_withString_throwsOnInvalid() {
        var pageable = PageRequest.of(0, 10);
        assertThrows(IllegalArgumentException.class,
                () -> service.listByStatusSummary("INVALID", pageable));
    }

    @Test
    void create_publishesEvent() {
        UUID consumerId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        UUID listingId = Instancio.create(UUID.class);
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingInfo(providerId, 5000L));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        service.create(consumerId, listingId, "notes");
        verify(eventPublisher).publishEvent(any());
    }

    @Test
    void confirm_allowsAdmin() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        assertDoesNotThrow(() -> service.confirm(id, authentication));
    }

    @Test
    void cancel_allowsAdmin() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        assertDoesNotThrow(() -> service.cancel(id, authentication));
    }

    @Test
    void getByIdForUser_allowsAdmin() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        assertDoesNotThrow(() -> service.getByIdForUser(id, authentication));
    }
}
