package com.marketplace.reviews;

import com.marketplace.shared.api.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * W1 §4.5 — the moderation queue's admin surface: the queue read's status
 * gate (blank = the queue's default, invalid = 400) and the decision command
 * (APPROVE publishes, REJECT hides, anything else = 400).
 *
 * <p>The controller is exercised directly here: the {@code hasRole('ADMIN')}
 * chain is the WebMvc/integration layer's assertion, this one owns the
 * parse-then-dispatch contract.
 */
class ReviewModerationAdminControllerTest {

    private final ReviewsService reviewsService = mock(ReviewsService.class);
    private final ReviewsViewService reviewsViewService = mock(ReviewsViewService.class);
    private final Pageable pageable = PageRequest.of(0, 20);

    private ReviewModerationAdminController controller;

    @BeforeEach
    void setUp() {
        controller = new ReviewModerationAdminController(reviewsService, reviewsViewService);
    }

    private Page<ReviewsService.ModerationQueueItem> emptyQueue() {
        return new PageImpl<>(List.of(), pageable, 0);
    }

    private static ReviewResponse composed(UUID reviewId, String status) {
        return new ReviewResponse(reviewId, null, 5, "text", null, null, null, null, null,
                "ORGANIC", status, null, "Sara", 1L, 0L);
    }

    @Test
    void queue_withoutStatus_readsThePendingDefault() {
        when(reviewsService.moderationQueue(eq(ReviewModerationStatus.PENDING_REVIEW), eq(pageable)))
                .thenReturn(emptyQueue());

        ResponseEntity<?> response = controller.queue(null, pageable);

        assertEquals(200, response.getStatusCode().value());
        verify(reviewsService).moderationQueue(ReviewModerationStatus.PENDING_REVIEW, pageable);
    }

    @Test
    void queue_withABlankStatus_stillReadsTheDefault() {
        when(reviewsService.moderationQueue(eq(ReviewModerationStatus.PENDING_REVIEW), eq(pageable)))
                .thenReturn(emptyQueue());

        controller.queue("   ", pageable);

        verify(reviewsService).moderationQueue(ReviewModerationStatus.PENDING_REVIEW, pageable);
    }

    @Test
    void queue_withAnExplicitStatus_parsesIt() {
        when(reviewsService.moderationQueue(any(), any())).thenReturn(emptyQueue());

        controller.queue("HIDDEN_BY_MODERATOR", pageable);

        verify(reviewsService).moderationQueue(ReviewModerationStatus.HIDDEN_BY_MODERATOR, pageable);
    }

    @Test
    void queue_withAnUnknownStatus_isAHonestBadRequest() {
        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> controller.queue("ARCHIVED", pageable));

        assertTrue(thrown.getMessage().contains("ARCHIVED"));
        assertTrue(thrown.getMessage().contains("PENDING_REVIEW"));
        verifyNoInteractions(reviewsService);
    }

    @Test
    void moderate_approve_publishesThroughTheService() {
        UUID reviewId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 5, "pending");
        when(reviewsService.approve(reviewId)).thenReturn(review);
        when(reviewsViewService.toResponse(review)).thenReturn(composed(reviewId, "PUBLISHED"));

        ResponseEntity<ReviewResponse> response = controller.moderate(reviewId,
                new ReviewModerationAdminController.ModerateReviewRequest("APPROVE"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("PUBLISHED", response.getBody().moderationStatus());
        verify(reviewsService).approve(reviewId);
    }

    @Test
    void moderate_reject_hidesThroughTheService() {
        UUID reviewId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, "spam");
        when(reviewsService.reject(reviewId)).thenReturn(review);
        when(reviewsViewService.toResponse(review))
                .thenReturn(composed(reviewId, "HIDDEN_BY_MODERATOR"));

        ResponseEntity<ReviewResponse> response = controller.moderate(reviewId,
                new ReviewModerationAdminController.ModerateReviewRequest("REJECT"));

        assertEquals("HIDDEN_BY_MODERATOR", response.getBody().moderationStatus());
        verify(reviewsService).reject(reviewId);
    }

    @Test
    void moderate_withAnUnknownAction_isAHonestBadRequest() {
        UUID reviewId = UUID.randomUUID();

        BadRequestException thrown = assertThrows(BadRequestException.class,
                () -> controller.moderate(reviewId,
                        new ReviewModerationAdminController.ModerateReviewRequest("DELETE")));

        assertTrue(thrown.getMessage().contains("DELETE"));
        assertTrue(thrown.getMessage().contains("APPROVE"));
        verifyNoInteractions(reviewsService, reviewsViewService);
    }

    @Test
    void moderate_toleratesSurroundingWhitespaceOnTheAction() {
        UUID reviewId = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 5, "ok");
        when(reviewsService.approve(reviewId)).thenReturn(review);
        when(reviewsViewService.toResponse(review)).thenReturn(composed(reviewId, "PUBLISHED"));

        controller.moderate(reviewId,
                new ReviewModerationAdminController.ModerateReviewRequest("  APPROVE  "));

        verify(reviewsService).approve(reviewId);
    }
}