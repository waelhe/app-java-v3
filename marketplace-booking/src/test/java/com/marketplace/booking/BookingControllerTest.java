package com.marketplace.booking;

import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock
    private BookingService bookingService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private BookingMapper bookingMapper;

    @InjectMocks
    private BookingController bookingController;

    @Test
    void getById_returnsBookingResponse() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        Booking booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, "Notes");
        BookingResponse response = new BookingResponse(id, UUID.randomUUID(), "PENDING", "Notes", null, null);

        when(bookingService.getByIdForUser(id, auth)).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        ResponseEntity<BookingResponse> result = bookingController.getById(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void create_createsBooking() {
        Authentication auth = mock(Authentication.class);
        UUID consumerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var request = new BookingController.CreateBookingRequest(listingId, "Notes");
        Booking booking = Booking.create(consumerId, UUID.randomUUID(), listingId, 5000L, "Notes");
        BookingResponse response = new BookingResponse(UUID.randomUUID(), listingId, "PENDING", "Notes", null, null);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(consumerId);
        when(bookingService.create(consumerId, listingId, "Notes")).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        ResponseEntity<BookingResponse> result = bookingController.create(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void confirm_returnsConfirmed() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        Booking booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        BookingResponse response = new BookingResponse(id, UUID.randomUUID(), "CONFIRMED", null, null, null);

        when(bookingService.confirm(id, auth)).thenReturn(booking);
        when(bookingMapper.toResponse(booking)).thenReturn(response);

        ResponseEntity<BookingResponse> result = bookingController.confirm(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("CONFIRMED", result.getBody().status());
    }

    @Test
    void listByConsumer_returnsPagedResponse() {
        UUID consumerId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        PageRequest pageable = PageRequest.of(0, 10);
        Booking booking = Booking.create(consumerId, UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        Page<Booking> page = new PageImpl<>(List.of(booking));
        when(bookingService.listByConsumer(consumerId, pageable, auth)).thenReturn(page);

        ResponseEntity<PagedResponse<BookingResponse>> result = bookingController.listByConsumer(consumerId, pageable, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void listByProvider_returnsPagedResponse() {
        UUID providerId = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        PageRequest pageable = PageRequest.of(0, 10);
        Booking booking = Booking.create(UUID.randomUUID(), providerId, UUID.randomUUID(), 5000L, null);
        Page<Booking> page = new PageImpl<>(List.of(booking));
        when(bookingService.listByProvider(providerId, pageable, auth)).thenReturn(page);

        ResponseEntity<PagedResponse<BookingResponse>> result = bookingController.listByProvider(providerId, pageable, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }
}
