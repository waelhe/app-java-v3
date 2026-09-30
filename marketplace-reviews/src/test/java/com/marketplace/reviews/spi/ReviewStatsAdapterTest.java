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


    @Test
    void findGeneralStatsByProviderId_readsTheGeneralAggregate() {
        UUID providerId = UUID.randomUUID();
        when(reviewRepository.getGeneralStatsByProviderId(providerId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 4.0, 9)));

        Optional<ReviewStats> stats = adapter.findGeneralStatsByProviderId(providerId);

        assertTrue(stats.isPresent());
        assertEquals(4.0, stats.get().averageRating());
        assertEquals(9, stats.get().reviewCount());
    }

    @Test
    void findGeneralStatsByProviderId_emptyWhenNoOrganicReviewExists() {
        UUID providerId = UUID.randomUUID();
        when(reviewRepository.getGeneralStatsByProviderId(providerId)).thenReturn(Optional.empty());

        assertTrue(adapter.findGeneralStatsByProviderId(providerId).isEmpty());
    }

    @Test
    void findGeneralStatsByReviewId_resolvesThroughTheReviewProvider() {
        UUID reviewId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        Review review = Review.createOrganic(UUID.randomUUID(), providerId,
                UUID.randomUUID(), 5, "organic");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));
        when(reviewRepository.getGeneralStatsByProviderId(providerId))
                .thenReturn(Optional.of(new ReviewStats(providerId, 4.5, 6)));

        Optional<ReviewStats> stats = adapter.findGeneralStatsByReviewId(reviewId);

        assertTrue(stats.isPresent());
        assertEquals(providerId, stats.get().providerId());
    }

    @Test
    void findGeneralStatsByReviewId_emptyWhenTheReviewIsUnknown() {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertTrue(adapter.findGeneralStatsByReviewId(reviewId).isEmpty());
    }

    @Test
    void findProviderUserIdByReviewId_answersTheProviderUserId() {
        UUID reviewId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), providerUserId, 4, "ok");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertEquals(Optional.of(providerUserId), adapter.findProviderUserIdByReviewId(reviewId));
    }

    @Test
    void findProviderUserIdByReviewId_resolvesEvenAHiddenRow() {
        UUID reviewId = UUID.randomUUID();
        UUID providerUserId = UUID.randomUUID();
        Review review = Review.createOrganic(UUID.randomUUID(), providerUserId,
                UUID.randomUUID(), 4, "hidden");
        review.hideByModerator();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertEquals(Optional.of(providerUserId), adapter.findProviderUserIdByReviewId(reviewId));
    }

    @Test
    void findProviderUserIdByReviewId_emptyWhenTheReviewIsUnknown() {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertTrue(adapter.findProviderUserIdByReviewId(reviewId).isEmpty());
    }
}
