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


    @Test
    void composed_toResponse_keepsTheEntityFieldsAndAddsTheThreeBlocks() {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Review review = new Review(id, bookingId, UUID.randomUUID(), UUID.randomUUID(), 4, "Nice");

        ReviewResponse response = mapper.toResponse(review, "Sara", 12L, 3L);

        assertEquals(id, response.id());
        assertEquals(bookingId, response.bookingId());
        assertEquals(4, response.rating());
        assertEquals("Nice", response.comment());
        assertEquals("Sara", response.reviewerName());
        assertEquals(12L, response.reviewerReviewCount());
        assertEquals(3L, response.helpfulCount());
    }

    @Test
    void composed_toResponse_carriesTheOrganicBadgeAndModerationState() {
        UUID reviewerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        Review review = Review.createOrganic(reviewerId, UUID.randomUUID(), listingId, 5, "organic");
        review.queueForReview();

        ReviewResponse response = mapper.toResponse(review, "Sara", 0L, 0L);

        assertEquals("ORGANIC", response.origin());
        assertEquals("PENDING_REVIEW", response.moderationStatus());
        assertEquals(listingId, response.listingId());
    }
}
