package com.marketplace.reviews;

import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewsControllerTest {

    @Mock
    private ReviewsService reviewsService;

    @Mock
    private ReviewsViewService reviewsViewService;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @InjectMocks
    private ReviewsController controller;

    /** The 15-field W1 response in its neutral shape (tests compare instances). */
    private static ReviewResponse response(UUID id) {
        return new ReviewResponse(id, null, null, null, null, null, null, null, null,
                null, null, null, null, 0L, 0L);
    }

    @Test
    void getById_returnsReview() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4, "Good");
        ReviewResponse response = response(id);

        when(reviewsViewService.getVisible(id, auth)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.getById(id, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void listByProvider_returnsPagedResponse() {
        UUID providerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(reviewsViewService.listByProvider(providerId, pageable))
                .thenReturn(new PageImpl<>(List.of(response(UUID.randomUUID()))));

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByProvider(providerId, pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void listByReviewer_returnsPagedResponse() {
        UUID reviewerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(reviewsViewService.listByReviewer(reviewerId, pageable, null))
                .thenReturn(new PageImpl<>(List.of(response(UUID.randomUUID()))));

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByReviewer(reviewerId, pageable, null);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void create_createsAndReturns201() {
        Authentication auth = mock(Authentication.class);
        UUID reviewerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var request = new ReviewsController.CreateReviewRequest(bookingId, 5, "Perfect");
        Review review = Review.create(bookingId, reviewerId, UUID.randomUUID(), 5, "Perfect");
        ReviewResponse response = response(UUID.randomUUID());

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(reviewerId);
        when(reviewsService.create(bookingId, reviewerId, 5, "Perfect")).thenReturn(review);
        when(reviewsViewService.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.create(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    /**
     * W1 §4.1/§4.2 — the organic write delegates to the service's mode-gated
     * path and answers 201 with the composed response.
     */
    @Test
    void createOrganic_delegatesToServiceAndReturns201() {
        Authentication auth = mock(Authentication.class);
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var request = new ReviewsController.CreateOrganicReviewRequest(providerId, listingId, 5, "Great bakery");
        Review review = Review.createOrganic(UUID.randomUUID(), UUID.randomUUID(), listingId, 5, "Great bakery");
        ReviewResponse response = response(UUID.randomUUID());

        when(reviewsService.createOrganic(providerId, listingId, 5, "Great bakery", auth))
                .thenReturn(review);
        when(reviewsViewService.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.createOrganic(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(reviewsService).createOrganic(providerId, listingId, 5, "Great bakery", auth);
    }

    @Test
    void update_updatesAndReturns200() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        var request = new ReviewsController.UpdateReviewRequest(4, "Updated");
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4, "Updated");
        ReviewResponse response = response(id);

        when(reviewsService.update(id, 4, "Updated", auth)).thenReturn(review);
        when(reviewsViewService.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.update(id, request, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void reply_delegatesToServiceAndReturns200() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        var request = new ReviewsController.ReplyRequest("Thanks for the feedback");
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4, "Good");
        ReviewResponse response = response(id);

        when(reviewsService.reply(id, "Thanks for the feedback", auth)).thenReturn(review);
        when(reviewsViewService.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.reply(id, request, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(reviewsService).reply(id, "Thanks for the feedback", auth);
    }

    @Test
    void createReverse_delegatesToServiceAndReturns201() {
        Authentication auth = mock(Authentication.class);
        UUID bookingId = UUID.randomUUID();
        var request = new ReviewsController.CreateReviewRequest(bookingId, 4, "Great guest");
        Review review = Review.createReverse(bookingId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 4, "Great guest");
        ReviewResponse response = response(UUID.randomUUID());

        when(reviewsService.createReverse(bookingId, 4, "Great guest", auth)).thenReturn(review);
        when(reviewsViewService.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.createReverse(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(reviewsService).createReverse(bookingId, 4, "Great guest", auth);
    }

    @Test
    void listByReviewee_delegatesToServiceAndReturns200() {
        UUID consumerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(reviewsViewService.listByReviewee(consumerId, pageable))
                .thenReturn(new PageImpl<>(List.of(response(UUID.randomUUID()))));

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByReviewee(consumerId, pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }
}
