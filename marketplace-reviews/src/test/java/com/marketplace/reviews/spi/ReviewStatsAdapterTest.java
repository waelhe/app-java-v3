package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.shared.api.ReviewStats;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewStatsAdapterTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ReviewStatsAdapter adapter = new ReviewStatsAdapter(reviewRepository);

    @Test
    void resolvesStatsThroughTheReviewProvider() {
        UUID reviewId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), providerId, 5, "great");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.getStatsByProviderId(providerId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 4.5, 2)));

        Optional<ReviewStats> stats = adapter.findStatsByReviewId(reviewId);

        assertTrue(stats.isPresent());
        assertEquals(providerId, stats.get().providerId());
        assertEquals(4.5, stats.get().averageRating());
        assertEquals(2, stats.get().reviewCount());
    }

    @Test
    void emptyWhenReviewDoesNotExist() {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertTrue(adapter.findStatsByReviewId(reviewId).isEmpty());
    }
}
