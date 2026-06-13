package com.marketplace.booking;

import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ListingPriceProvider.ListingInfo;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import org.instancio.Instancio;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import static org.instancio.Select.field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingServiceTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final ListingPriceProvider listingPriceProvider = mock(ListingPriceProvider.class);
    private final AvailabilityPort availabilityPort = mock(AvailabilityPort.class);
    private final Authentication authentication = mock(Authentication.class);
    private BookingService service;

    @BeforeEach
    void setUp() {
        service = new BookingService(bookingRepository, currentUserProvider, eventPublisher, listingPriceProvider, availabilityPort);
    }

    @Test
    void create_setsStatusToPending() {
        UUID consumerId = Instancio.create(UUID.class);
        UUID providerId = Instancio.create(UUID.class);
        UUID listingId = Instancio.create(UUID.class);
        Instant now = Instant.now();

        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingInfo(providerId, 5000L));
        when(availabilityPort.isAvailable(providerId, now, now.plusSeconds(3600))).thenReturn(true);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Booking booking = service.create(consumerId, listingId, now, now.plusSeconds(3600), "test notes");

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
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
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
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
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
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
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
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
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
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.cancel(id, authentication));
    }

    @Test
    void getByIdForUser_success() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getId), id)
                .set(field(Booking::getConsumerId), userId)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        Booking result = service.getByIdForUser(id, authentication);

        assertNotNull(result);
        assertEquals(id, result.getId());
    }

    @Test
    void getByIdForUser_throwsWhenNotParticipant() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(Instancio.create(UUID.class));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThrows(AccessDeniedException.class, () -> service.getByIdForUser(id, authentication));
    }

    @Test
    void listByConsumer_returnsBookings() {
        UUID consumerId = Instancio.create(UUID.class);
        Pageable pageable = Pageable.unpaged();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(bookingRepository.findByConsumerId(consumerId, pageable))
                .thenReturn(Page.empty());

        var result = service.listByConsumer(consumerId, pageable, authentication);

        assertNotNull(result);
    }

    @Test
    void listByProvider_returnsBookings() {
        UUID providerId = Instancio.create(UUID.class);
        Pageable pageable = Pageable.unpaged();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(providerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(bookingRepository.findByProviderId(providerId, pageable))
                .thenReturn(Page.empty());

        var result = service.listByProvider(providerId, pageable, authentication);

        assertNotNull(result);
    }

    @Test
    void listByStatus_returnsBookings() {
        Pageable pageable = Pageable.unpaged();
        when(bookingRepository.findByStatus(BookingStatus.PENDING, pageable))
                .thenReturn(Page.empty());

        var result = service.listByStatus(BookingStatus.PENDING, pageable);

        assertNotNull(result);
    }

    @Test
    void listAll_returnsBookings() {
        Pageable pageable = Pageable.unpaged();
        when(bookingRepository.findAll(pageable)).thenReturn(Page.empty());

        var result = service.listAll(pageable);

        assertNotNull(result);
    }

    @Test
    void listAllSummaries_returnsSummaries() {
        Pageable pageable = Pageable.unpaged();
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(booking)));

        var result = service.listAllSummaries(pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void autoCancel_cancelsPendingBooking() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        service.autoCancel(id);

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    }

    @Test
    void autoCancel_alreadyCancelled_doesNothing() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.CANCELLED)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        service.autoCancel(id);

        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    }

    @Test
    void autoConfirm_confirmsPendingBooking() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        service.autoConfirm(id);

        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    @Test
    void autoConfirm_alreadyConfirmed_doesNothing() {
        UUID id = Instancio.create(UUID.class);
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.CONFIRMED)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        service.autoConfirm(id);

        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    @Test
    void listByStatusSummary_withString_returnsSummaries() {
        Pageable pageable = Pageable.unpaged();
        Booking booking = Instancio.of(Booking.class)
                .set(field(Booking::getPriceCents), 5000L)
                .set(field(Booking::getNotes), "notes")
                .set(field(Booking::getStatus), BookingStatus.PENDING)
                .set(field(Booking::getStartsAt), null)
                .set(field(Booking::getEndsAt), null)
                .create();
        when(bookingRepository.findByStatus(BookingStatus.PENDING, pageable))
                .thenReturn(new PageImpl<>(List.of(booking)));

        var result = service.listByStatusSummary("PENDING", pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void listByStatusSummary_withInvalidString_throws() {
        Pageable pageable = Pageable.unpaged();

        assertThrows(com.marketplace.shared.api.BadRequestException.class,
                () -> service.listByStatusSummary("INVALID", pageable));
    }
}
