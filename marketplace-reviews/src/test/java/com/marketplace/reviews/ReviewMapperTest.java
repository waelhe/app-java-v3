package com.marketplace.reviews;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReviewMapperTest {

    private final ReviewMapper mapper = Mappers.getMapper(ReviewMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Review review = new Review(id, bookingId, UUID.randomUUID(), UUID.randomUUID(), 4, "Nice");

        ReviewResponse response = mapper.toResponse(review);

        assertEquals(id, response.id());
        assertEquals(bookingId, response.bookingId());
        assertEquals(4, response.rating());
        assertEquals("Nice", response.comment());
    }
}
