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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

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

    /** The 16-field W1+W4 response in its neutral shape (tests compare instances). */
    private static ReviewResponse response(UUID id) {
        return new ReviewResponse(id, null, null, null, null, null, null, null, null,
                null, null, null, null, null, 0L, 0L);
    }

    /** B-09: a REAL WebRequest over a mock request — checkNotModified's genuine behavior. */
    private static ServletWebRequest webRequest() {
        return new ServletWebRequest(new MockHttpServletRequest());
    }

    /** B-09: a WebRequest whose If-None-Match carries the given ETag header value. */
    private static ServletWebRequest webRequestMatching(String eTagHeaderValue) {
        MockHttpServletRequest servlet = new MockHttpServletRequest();
        servlet.addHeader("If-None-Match", eTagHeaderValue);
        return new ServletWebRequest(servlet);
    }

    @Test
    void getById_returnsReview() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        ReviewResponse response = response(id);

        when(reviewsViewService.getVisible(id, auth)).thenReturn(response);

        ResponseEntity<ReviewResponse> result = controller.getById(id, auth, webRequest());

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(response, result.getBody());
        assertNotNull(result.getHeaders().getETag());
    }

    /**
     * B-09 (compliance plan B.3 — the 304 gate): the conditional roundtrip —
     * the first read answers 200 with the content-fingerprint ETag, and the
     * revalidation carrying it back answers 304 with NO body.
     */
    @Test
    void getById_conditional_answers304OnTheRevalidation() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        ReviewResponse response = response(id);
        when(reviewsViewService.getVisible(id, auth)).thenReturn(response);

        ResponseEntity<ReviewResponse> first = controller.getById(id, auth, webRequest());
        assertEquals(HttpStatus.OK, first.getStatusCode());
        String eTag = first.getHeaders().getETag();
        assertNotNull(eTag);

        ResponseEntity<ReviewResponse> revalidated =
                controller.getById(id, auth, webRequestMatching(eTag));

        assertEquals(HttpStatus.NOT_MODIFIED, revalidated.getStatusCode());
        assertNull(revalidated.getBody());
        assertEquals(eTag, revalidated.getHeaders().getETag());
    }

    /**
     * B-09 (B.3): the fingerprint is over the CONTENT — a changed row (any
     * visible field, here the reply) produces a DIFFERENT tag, so the
     * client's stale If-None-Match gets a fresh 200, never a false 304.
     */
    @Test
    void getById_conditional_contentChangeBreaksTheMatch() {
        UUID id = UUID.randomUUID();
        Authentication auth = mock(Authentication.class);
        when(reviewsViewService.getVisible(id, auth)).thenReturn(response(id));

        ResponseEntity<ReviewResponse> first = controller.getById(id, auth, webRequest());
        String staleTag = first.getHeaders().getETag();

        when(reviewsViewService.getVisible(id, auth)).thenReturn(new ReviewResponse(
                id, null, null, null, "the provider replied", null, null, null, null,
                null, null, null, null, null, 0L, 0L));

        ResponseEntity<ReviewResponse> after = controller.getById(id, auth, webRequestMatching(staleTag));

        assertEquals(HttpStatus.OK, after.getStatusCode());
        assertNotNull(after.getBody());
        assertNotEquals(staleTag, after.getHeaders().getETag());
    }

    @Test
    void listByProvider_returnsPagedResponse() {
        UUID providerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(reviewsViewService.listByProvider(providerId, pageable))
                .thenReturn(new PageImpl<>(List.of(response(UUID.randomUUID()))));

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByProvider(providerId, pageable, webRequest());

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    /**
     * B-09 (B.3 — the 304 gate on the list surface): the page roundtrip —
     * 200 with the page fingerprint, then the matching revalidation gets
     * 304 with no body.
     */
    @Test
    void listByProvider_conditional_answers304OnTheRevalidation() {
        UUID providerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(reviewsViewService.listByProvider(providerId, pageable))
                .thenReturn(new PageImpl<>(List.of(response(UUID.randomUUID()))));

        ResponseEntity<PagedResponse<ReviewResponse>> first =
                controller.listByProvider(providerId, pageable, webRequest());
        assertEquals(HttpStatus.OK, first.getStatusCode());
        String eTag = first.getHeaders().getETag();
        assertNotNull(eTag);

        ResponseEntity<PagedResponse<ReviewResponse>> revalidated =
                controller.listByProvider(providerId, pageable, webRequestMatching(eTag));

        assertEquals(HttpStatus.NOT_MODIFIED, revalidated.getStatusCode());
        assertNull(revalidated.getBody());
        assertEquals(eTag, revalidated.getHeaders().getETag());
    }

    @Test
    void listByReviewer_returnsPagedResponse() {
        UUID reviewerId = UUID.randomUUID();
        PageRequest pageable = PageRequest.of(0, 10);
        when(reviewsViewService.listByReviewer(reviewerId, pageable, null))
                .thenReturn(new PageImpl<>(List.of(response(UUID.randomUUID()))));

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByReviewer(reviewerId, pageable, null, webRequest());

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

        ResponseEntity<PagedResponse<ReviewResponse>> result = controller.listByReviewee(consumerId, pageable, webRequest());

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }
}
