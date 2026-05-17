package com.marketplace.booking;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingParticipantProviderAdapterTest {

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private BookingParticipantProviderAdapter adapter;

    @Test
    void getBookingInfo_returnsInfo() {
        UUID id = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Booking booking = new Booking(id, consumerId, providerId, UUID.randomUUID(), 5000L, "Notes");

        when(bookingRepository.findById(id)).thenReturn(Optional.of(booking));

        BookingInfo info = adapter.getBookingInfo(id);

        assertEquals(consumerId, info.consumerId());
        assertEquals(providerId, info.providerId());
        assertEquals("PENDING", info.status());
        assertEquals(5000L, info.priceCents());
    }

    @Test
    void getBookingInfo_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(bookingRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> adapter.getBookingInfo(id));
    }
}
