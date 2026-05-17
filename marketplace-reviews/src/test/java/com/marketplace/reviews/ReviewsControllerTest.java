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
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ReviewMapper reviewMapper;

    @InjectMocks
    private ReviewsController controller;

    @Test
    void getById_returnsReview() {
        UUID id = UUID.randomUUID();
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4, "Good");
        ReviewResponse response = new ReviewResponse(id, UUID.randomUUID(), 4, "Good", null, null);

        when(reviewsService.getById(id)).thenReturn(review);
        when(reviewMapper.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.getById(id);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void listByProvider_returnsPagedResponse() {
        UUID providerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), providerId, 5, "Great");
        Page<Review> page = new PageImpl<>(List.of(review));
        when(reviewsService.listByProvider(providerId, pageable)).thenReturn(page);

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByProvider(providerId, pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void listByReviewer_returnsPagedResponse() {
        UUID reviewerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        Review review = Review.create(UUID.randomUUID(), reviewerId, UUID.randomUUID(), 5, "Great");
        Page<Review> page = new PageImpl<>(List.of(review));
        when(reviewsService.listByReviewer(reviewerId, pageable)).thenReturn(page);

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByReviewer(reviewerId, pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void create_createsAndReturns201() {
        Authentication auth = mock(Authentication.class);
        UUID reviewerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var request = new ReviewsController.CreateReviewRequest(bookingId, 5, "Perfect");
        Review review = Review.create(bookingId, reviewerId, UUID.randomUUID(), 5, "Perfect");
        ReviewResponse response = new ReviewResponse(UUID.randomUUID(), bookingId, 5, "Perfect", null, null);

        when(currentUserProvider.getCurrentUserId(auth)).thenReturn(reviewerId);
        when(reviewsService.create(bookingId, reviewerId, 5, "Perfect")).thenReturn(review);
        when(reviewMapper.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.create(request, auth);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(response, result.getBody());
    }

    @Test
    void update_updatesAndReturns200() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        var request = new ReviewsController.UpdateReviewRequest(4, "Updated");
        Review review = Review.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 4, "Updated");
        ReviewResponse response = new ReviewResponse(id, UUID.randomUUID(), 4, "Updated", null, null);

        when(reviewsService.update(id, 4, "Updated", auth)).thenReturn(review);
        when(reviewMapper.toResponse(review)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.update(id, request, auth);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
    }
}
