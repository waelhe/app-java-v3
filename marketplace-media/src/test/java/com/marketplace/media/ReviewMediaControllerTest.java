package com.marketplace.media;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1 §4.4 — the review-media HTTP surface: the four endpoints' status codes
 * and their delegation to the service (the business rules belong to
 * {@link ReviewMediaServiceTest}).
 */
class ReviewMediaControllerTest {

    private final ReviewMediaService reviewMediaService = mock(ReviewMediaService.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final Authentication authentication = mock(Authentication.class);

    private ReviewMediaController controller;

    @BeforeEach
    void setUp() {
        controller = new ReviewMediaController(reviewMediaService, currentUserProvider);
    }

    @Test
    void requestUpload_answersCreated() {
        UUID reviewId = UUID.randomUUID();
        when(reviewMediaService.requestUpload(reviewId, "image/jpeg", 1024L, authentication))
                .thenReturn(new ReviewMediaService.ReviewMediaUploadView(
                        UUID.randomUUID(), "review-media/key.jpg", "https://upload.test",
                        Duration.ofMinutes(15)));

        ResponseEntity<ReviewMediaService.ReviewMediaUploadView> response = controller.requestUpload(
                new ReviewMediaController.RequestReviewUploadRequest(reviewId, "image/jpeg", 1024L),
                authentication);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("https://upload.test", response.getBody().uploadUrl());
    }

    @Test
    void confirmUpload_answersOk() {
        UUID mediaId = UUID.randomUUID();
        when(reviewMediaService.confirmUpload(mediaId, authentication))
                .thenReturn(view(mediaId));

        ResponseEntity<ReviewMediaService.ReviewMediaView> response =
                controller.confirmUpload(mediaId, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("UPLOADED", response.getBody().status());
    }

    @Test
    void listByReview_answersOkWithTheOrderedViews() {
        UUID reviewId = UUID.randomUUID();
        when(reviewMediaService.listByReview(reviewId, authentication))
                .thenReturn(List.of(view(reviewId), view(reviewId)));

        ResponseEntity<List<ReviewMediaService.ReviewMediaView>> response =
                controller.listByReview(reviewId, authentication);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void delete_answersNoContent() {
        UUID mediaId = UUID.randomUUID();

        ResponseEntity<Void> response = controller.delete(mediaId, authentication);

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(reviewMediaService).delete(mediaId, authentication);
    }

    private static ReviewMediaService.ReviewMediaView view(UUID reviewId) {
        return new ReviewMediaService.ReviewMediaView(UUID.randomUUID(), reviewId,
                "image/jpeg", 1024L, "UPLOADED", 1, "https://download.test", null);
    }
}