package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewModerationStatus;
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
        // The SCALAR projection — the adapter passes the PUBLISHED policy
        // itself, and never materializes the entity (the report-survival
        // root: an entity here would hold the pre-hide @Version snapshot
        // in the creation transaction's persistence context).
        when(reviewRepository.findReviewerIdByIdAndModerationStatus(
                reviewId, ReviewModerationStatus.PUBLISHED))
                .thenReturn(Optional.of(reviewerId));

        assertEquals(Optional.of(reviewerId), adapter.findVisibleAuthorId(reviewId));
        verify(reviewRepository).findReviewerIdByIdAndModerationStatus(
                reviewId, ReviewModerationStatus.PUBLISHED);
    }

    @Test
    void findVisibleAuthorId_answersEmptyForEverythingElse() {
        UUID reviewId = UUID.randomUUID();
        // Unknown, soft-deleted, pending or moderator-hidden — the
        // PUBLISHED-scoped projection answers empty; the real-DB pins are
        // ReviewReportIntegrationTest's honest 404s and the report-survival
        // serialization test (RowLockSerializationIntegrationTest).
        when(reviewRepository.findReviewerIdByIdAndModerationStatus(
                reviewId, ReviewModerationStatus.PUBLISHED))
                .thenReturn(Optional.empty());

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

    // -- B-19/C.11 row 3: the automatic engine's narrow internal path ----

    @Test
    void hideAsModerationEngine_delegatesToTheOwningService() {
        UUID reviewId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        when(reviewsService.hideByModerationEngine(reviewId)).thenReturn(Optional.of(reviewerId));

        assertEquals(Optional.of(reviewerId), adapter.hideAsModerationEngine(reviewId));
        verify(reviewsService).hideByModerationEngine(reviewId);
        // The engine path never touches the admin-gated manual method —
        // one physics, two authority paths.
        verify(reviewsService, org.mockito.Mockito.never()).hideByModerator(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(reviewRepository);
    }

    @Test
    void hideAsModerationEngine_passesTheSkipThroughWhenTheGoalIsAlreadyMet() {
        UUID reviewId = UUID.randomUUID();
        when(reviewsService.hideByModerationEngine(reviewId)).thenReturn(Optional.empty());

        assertTrue(adapter.hideAsModerationEngine(reviewId).isEmpty());
    }
}