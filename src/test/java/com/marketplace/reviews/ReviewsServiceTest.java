package com.marketplace.reviews;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewsServiceTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ReviewsService service = new ReviewsService(reviewRepository);

    @Test
    void create_validReview() {
        UUID bookingId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();

        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(false);
        when(reviewRepository.save(any(Review.class))).thenAnswer(inv -> inv.getArgument(0));

        Review review = service.create(bookingId, reviewerId, providerId, 4, "Great service");

        assertEquals(4, review.getRating());
        assertEquals("Great service", review.getComment());
    }

    @Test
    void create_rejectsInvalidRating() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, "bad"));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 6, "bad"));
    }

    @Test
    void create_rejectsDuplicateReview() {
        UUID bookingId = UUID.randomUUID();
        when(reviewRepository.existsByBookingId(bookingId)).thenReturn(true);

        assertThrows(IllegalStateException.class,
                () -> service.create(bookingId, UUID.randomUUID(), UUID.randomUUID(), 3, "dup"));
    }

    @Test
    void update_changesRatingAndComment() {
        UUID id = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 3, "ok");
        when(reviewRepository.findById(id)).thenReturn(Optional.of(review));

        Review updated = service.update(id, 5, "excellent");

        assertEquals(5, updated.getRating());
        assertEquals("excellent", updated.getComment());
    }
}