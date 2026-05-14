package com.marketplace.booking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BookingResponseTest {

    @Test
    void fromMapsBooking() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, "notes");

        var response = BookingResponse.from(booking);

        assertEquals(booking.getId(), response.id());
        assertEquals(booking.getListingId(), response.listingId());
        assertEquals(BookingStatus.PENDING.name(), response.status());
        assertEquals("notes", response.notes());
        assertEquals(booking.getCreatedAt(), response.createdAt());
        assertEquals(booking.getUpdatedAt(), response.updatedAt());
    }
}
