package com.marketplace.booking;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingParticipantProviderAdapterTest {

    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private BookingParticipantProviderAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BookingParticipantProviderAdapter(bookingRepository);
    }

    @Test
    void getBookingInfoReturnsInfo() {
        var bookingId = UUID.randomUUID();
        var consumerId = UUID.randomUUID();
        var providerId = UUID.randomUUID();
        var listingId = UUID.randomUUID();
        var booking = new Booking(bookingId, consumerId, providerId, listingId, 5000L, "notes");
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));

        var info = adapter.getBookingInfo(bookingId);

        assertEquals(providerId, info.providerId());
        assertEquals(consumerId, info.consumerId());
        assertEquals(BookingStatus.PENDING.name(), info.status());
        assertEquals(5000L, info.priceCents());
    }

    @Test
    void getBookingInfoThrowsWhenNotFound() {
        var bookingId = UUID.randomUUID();
        when(bookingRepository.findById(bookingId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> adapter.getBookingInfo(bookingId));
    }
}
