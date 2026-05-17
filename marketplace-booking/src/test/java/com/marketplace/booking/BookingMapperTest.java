package com.marketplace.booking;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BookingMapperTest {

    private final BookingMapper mapper = Mappers.getMapper(BookingMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        Booking booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), listingId, 10000L, "Test notes");

        BookingResponse response = mapper.toResponse(booking);

        assertEquals(booking.getId(), response.id());
        assertEquals(listingId, response.listingId());
        assertEquals("PENDING", response.status());
        assertEquals("Test notes", response.notes());
    }
}
