package com.marketplace.reviews;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1 (yelp-level plan §4.4/§4.5) — the read-side assembly: the three
 * batch-resolved blocks (reviewer name, published-review count,
 * helpful-vote count) and the honest fallbacks when a batch returns no row.
 *
 * <p>The batch discipline itself is asserted: a page costs one
 * {@code findAllByIds}, one grouped count query and one grouped vote query —
 * never a per-review lookup (the N+1 the port's Javadoc forbids).
 */
class ReviewsViewServiceTest {

    private final ReviewsService reviewsService = mock(ReviewsService.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ReviewVoteRepository reviewVoteRepository = mock(ReviewVoteRepository.class);
    private final UserLookupPort userLookupPort = mock(UserLookupPort.class);
    private final ReviewMapper reviewMapper = Mappers.getMapper(ReviewMapper.class);
    private final Authentication authentication = mock(Authentication.class);
    private final Pageable pageable = PageRequest.of(0, 20);

    private ReviewsViewService viewService;

    @BeforeEach
    void setUp() {
        viewService = new ReviewsViewService(reviewsService, reviewRepository,
                reviewVoteRepository, userLookupPort, reviewMapper);
    }

    private static UserSummary summary(UUID id, String displayName, Instant pseudonymizedAt) {
        return new UserSummary(id, "user@example.com", displayName, "USER",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                pseudonymizedAt);
    }

    @Test
    void toResponse_composesTheThreeBlocks() {
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.createOrganic(reviewerId, UUID.randomUUID(), UUID.randomUUID(), 5, "great");
        when(userLookupPort.findAllByIds(anyCollection()))
                .thenReturn(Map.of(reviewerId, summary(reviewerId, "Sara", null)));
        when(reviewRepository.countPublishedByReviewerIds(anyCollection()))
                .thenReturn(List.of(new ReviewerReviewCount(reviewerId, 12L)));
        when(reviewVoteRepository.countByReviewIds(anyCollection()))
                .thenReturn(List.of(new ReviewVoteCount(review.getId(), 3L)));

        ReviewResponse response = viewService.toResponse(review);

        assertEquals("Sara", response.reviewerName());
        assertEquals(12L, response.reviewerReviewCount());
        assertEquals(3L, response.helpfulCount());
        assertEquals("ORGANIC", response.origin());
    }

    @Test
    void assemble_fallsBackWhenTheBatchesReturnNoRow() {
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.create(reviewerId, reviewerId, UUID.randomUUID(), 4, "ok");
        when(userLookupPort.findAllByIds(anyCollection())).thenReturn(Map.of());
        when(reviewRepository.countPublishedByReviewerIds(anyCollection())).thenReturn(List.of());
        when(reviewVoteRepository.countByReviewIds(anyCollection())).thenReturn(List.of());

        ReviewResponse response = viewService.toResponse(review);

        assertEquals("User", response.reviewerName());
        assertEquals(0L, response.reviewerReviewCount());
        assertEquals(0L, response.helpfulCount());
    }

    @Test
    void assemble_honoursThePseudonymizationMarker() {
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.createOrganic(reviewerId, UUID.randomUUID(), UUID.randomUUID(), 3, "fine");
        when(userLookupPort.findAllByIds(anyCollection())).thenReturn(Map.of(
                reviewerId, summary(reviewerId, "Real Name", Instant.parse("2026-05-01T00:00:00Z"))));
        when(reviewRepository.countPublishedByReviewerIds(anyCollection())).thenReturn(List.of());
        when(reviewVoteRepository.countByReviewIds(anyCollection())).thenReturn(List.of());

        assertEquals(UserSummary.FORMER_MEMBER_LABEL, viewService.toResponse(review).reviewerName());
    }

    @Test
    void assemble_onAnEmptyPage_costsNoQueryAtAll() {
        when(reviewsService.listByProvider(any(), any())).thenReturn(Page.empty());

        Page<ReviewResponse> page = viewService.listByProvider(UUID.randomUUID(), pageable);

        assertTrue(page.getContent().isEmpty());
        verify(userLookupPort, never()).findAllByIds(anyCollection());
        verify(reviewRepository, never()).countPublishedByReviewerIds(anyCollection());
        verify(reviewVoteRepository, never()).countByReviewIds(anyCollection());
    }

    @Test
    void listByProvider_resolvesTheWholePageInThreeBatches() {
        UUID reviewerId = UUID.randomUUID();
        Review first = Review.createOrganic(reviewerId, UUID.randomUUID(), UUID.randomUUID(), 5, "a");
        Review second = Review.createOrganic(reviewerId, UUID.randomUUID(), UUID.randomUUID(), 1, "b");
        when(reviewsService.listByProvider(any(), any()))
                .thenReturn(new PageImpl<>(List.of(first, second), pageable, 7));
        when(userLookupPort.findAllByIds(anyCollection()))
                .thenReturn(Map.of(reviewerId, summary(reviewerId, "Sara", null)));
        when(reviewRepository.countPublishedByReviewerIds(anyCollection()))
                .thenReturn(List.of(new ReviewerReviewCount(reviewerId, 2L)));
        when(reviewVoteRepository.countByReviewIds(anyCollection()))
                .thenReturn(List.of(
                        new ReviewVoteCount(first.getId(), 5L),
                        new ReviewVoteCount(second.getId(), 0L)));

        Page<ReviewResponse> page = viewService.listByProvider(UUID.randomUUID(), pageable);

        assertEquals(2, page.getContent().size());
        // PageImpl's own arithmetic: a 2-row page inside a 20-row window with
        // an upstream total of 7 cannot claim 7 total (0 + 20 > 7 collapses
        // the total to offset + content). Asserted as the honest value the
        // contract actually produces, not the number I wished for.
        assertEquals(2, page.getTotalElements());
        assertEquals(5L, page.getContent().get(0).helpfulCount());
        assertEquals(0L, page.getContent().get(1).helpfulCount());
        verify(userLookupPort).findAllByIds(anyCollection());
        verify(reviewRepository).countPublishedByReviewerIds(anyCollection());
        verify(reviewVoteRepository).countByReviewIds(anyCollection());
    }

    @Test
    void listByReviewer_delegatesWithTheCallersAuthentication() {
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.createOrganic(reviewerId, UUID.randomUUID(), UUID.randomUUID(), 4, "a");
        when(reviewsService.listByReviewer(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(review), pageable, 1));
        when(userLookupPort.findAllByIds(anyCollection())).thenReturn(Map.of());
        when(reviewRepository.countPublishedByReviewerIds(anyCollection())).thenReturn(List.of());
        when(reviewVoteRepository.countByReviewIds(anyCollection())).thenReturn(List.of());

        Page<ReviewResponse> page = viewService.listByReviewer(reviewerId, pageable, authentication);

        assertEquals(1, page.getContent().size());
        verify(reviewsService).listByReviewer(reviewerId, pageable, authentication);
    }

    @Test
    void listByReviewee_composesTheSameBlocks() {
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.create(reviewerId, reviewerId, UUID.randomUUID(), 2, "a");
        when(reviewsService.listByReviewee(any(), any()))
                .thenReturn(new PageImpl<>(List.of(review), pageable, 1));
        when(userLookupPort.findAllByIds(anyCollection()))
                .thenReturn(Map.of(reviewerId, summary(reviewerId, "Omar", null)));
        when(reviewRepository.countPublishedByReviewerIds(anyCollection())).thenReturn(List.of());
        when(reviewVoteRepository.countByReviewIds(anyCollection())).thenReturn(List.of());

        Page<ReviewResponse> page = viewService.listByReviewee(UUID.randomUUID(), pageable);

        assertEquals("Omar", page.getContent().get(0).reviewerName());
    }

    @Test
    void getVisible_readsThroughTheVisibilityGate() {
        UUID reviewId = UUID.randomUUID();
        UUID reviewerId = UUID.randomUUID();
        Review review = Review.create(reviewerId, reviewerId, UUID.randomUUID(), 5, "visible");
        when(reviewsService.getById(review.getId())).thenReturn(review);
        when(userLookupPort.findAllByIds(anyCollection()))
                .thenReturn(Map.of(reviewerId, summary(reviewerId, "Sara", null)));
        when(reviewRepository.countPublishedByReviewerIds(anyCollection())).thenReturn(List.of());
        when(reviewVoteRepository.countByReviewIds(anyCollection())).thenReturn(List.of());

        ReviewResponse response = viewService.getVisible(review.getId(), authentication);

        assertEquals(review.getId(), response.id());
        assertEquals("Sara", response.reviewerName());
    }

    @Test
    void getVisible_propagatesTheGatesHonest404() {
        UUID reviewId = UUID.randomUUID();
        Review review = Review.create(reviewerIdOf(reviewId), reviewerIdOf(reviewId),
                UUID.randomUUID(), 5, "hidden");
        when(reviewsService.getById(reviewId)).thenReturn(review);
        doThrow(new ResourceNotFoundException("Review not found: " + reviewId))
                .when(reviewsService).assertVisible(any(Review.class), eq(authentication));

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> viewService.getVisible(reviewId, authentication));

        assertTrue(thrown.getMessage().contains(reviewId.toString()));
    }

    private static UUID reviewerIdOf(UUID reviewId) {
        return UUID.nameUUIDFromBytes(reviewId.toString().getBytes());
    }
}