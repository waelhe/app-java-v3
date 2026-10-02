package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.reviews.ReviewsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W1 §4.5 — the {@code ReviewLookupPort} contract the community module's
 * report-resolve rides: the two read gates and the moderation hide, which
 * delegates to the owning service so the state machine and its events stay
 * in one place.
 */
class ReviewsLookupAdapterTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ReviewsService reviewsService = mock(ReviewsService.class);

    private ReviewsLookupAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReviewsLookupAdapter(reviewRepository, reviewsService);
    }

    @Test
    void findVisibleAuthorId_resolvesAPublishedReview() {
        UUID reviewId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), reviewerId, UUID.randomUUID(), 5, "ok");
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertEquals(Optional.of(reviewerId), adapter.findVisibleAuthorId(reviewId));
    }

    @Test
    void findVisibleAuthorId_hidesANonPublishedRow() {
        UUID reviewId = UUID.randomUUID();
        Review review = Review.createOrganic(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 5, "pending");
        review.queueForReview();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertTrue(adapter.findVisibleAuthorId(reviewId).isEmpty());
    }

    @Test
    void findVisibleAuthorId_emptyForAnUnknownReview() {
        UUID reviewId = UUID.randomUUID();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.empty());

        assertTrue(adapter.findVisibleAuthorId(reviewId).isEmpty());
    }

    @Test
    void findAuthorId_answersRegardlessOfTheModerationState() {
        UUID reviewId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.createOrganic(reviewerId, UUID.randomUUID(),
                UUID.randomUUID(), 5, "pending");
        review.queueForReview();
        when(reviewRepository.findById(reviewId)).thenReturn(Optional.of(review));

        assertEquals(Optional.of(reviewerId), adapter.findAuthorId(reviewId));
    }

    @Test
    void hideAsModerator_delegatesToTheOwningService() {
        UUID reviewId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        when(reviewsService.hideByModerator(reviewId)).thenReturn(Optional.of(reviewerId));

        assertEquals(Optional.of(reviewerId), adapter.hideAsModerator(reviewId));
        verify(reviewsService).hideByModerator(reviewId);
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void hideAsModerator_passesTheSkipThroughWhenTheGoalIsAlreadyMet() {
        UUID reviewId = UUID.randomUUID();
        when(reviewsService.hideByModerator(reviewId)).thenReturn(Optional.empty());

        assertTrue(adapter.hideAsModerator(reviewId).isEmpty());
    }
}