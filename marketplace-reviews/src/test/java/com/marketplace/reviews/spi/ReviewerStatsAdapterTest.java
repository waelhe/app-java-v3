package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewerOriginCount;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.reviews.ReviewVoteRepository;
import com.marketplace.shared.api.ReviewerStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W4 (yelp-level plan §5 — G28/G29) — the reviewer-profile facts seam: the
 * origin-split published counters (one grouped query, absent origins read as
 * zero) and the cumulative helpful-vote total (one count query), composed
 * into the neutral {@link ReviewerStats} record the identity page renders.
 */
class ReviewerStatsAdapterTest {

    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ReviewVoteRepository reviewVoteRepository = mock(ReviewVoteRepository.class);

    private ReviewerStatsAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReviewerStatsAdapter(reviewRepository, reviewVoteRepository);
    }

    @Test
    void findReviewerStats_splitsThePublishedCountersByOrigin() {
        UUID reviewerId = UUID.randomUUID();
        when(reviewRepository.countPublishedByOriginForReviewer(reviewerId)).thenReturn(List.of(
                new ReviewerOriginCount(Review.ORIGIN_BOOKING, 4L),
                new ReviewerOriginCount(Review.ORIGIN_ORGANIC, 12L)));
        when(reviewVoteRepository.countByReviewerId(reviewerId)).thenReturn(9L);

        ReviewerStats stats = adapter.findReviewerStats(reviewerId);

        assertEquals(4L, stats.verifiedReviewCount());
        assertEquals(12L, stats.organicReviewCount());
        assertEquals(9L, stats.helpfulVoteCount());
    }

    @Test
    void findReviewerStats_anOriginWithNoRowsReadsAsZero() {
        // The grouping produces a row only for origins that HAVE rows — a
        // VERIFIED_ONLY world (every review born BOOKING) answers one row
        // and the organic counter honestly reads zero.
        UUID reviewerId = UUID.randomUUID();
        when(reviewRepository.countPublishedByOriginForReviewer(reviewerId))
                .thenReturn(List.of(new ReviewerOriginCount(Review.ORIGIN_BOOKING, 3L)));
        when(reviewVoteRepository.countByReviewerId(reviewerId)).thenReturn(0L);

        ReviewerStats stats = adapter.findReviewerStats(reviewerId);

        assertEquals(3L, stats.verifiedReviewCount());
        assertEquals(0L, stats.organicReviewCount());
        assertEquals(0L, stats.helpfulVoteCount());
    }

    @Test
    void findReviewerStats_aNeverReviewerAnswersTheAllZeroInstance() {
        UUID reviewerId = UUID.randomUUID();
        when(reviewRepository.countPublishedByOriginForReviewer(reviewerId)).thenReturn(List.of());
        when(reviewVoteRepository.countByReviewerId(reviewerId)).thenReturn(0L);

        ReviewerStats stats = adapter.findReviewerStats(reviewerId);

        assertEquals(0L, stats.verifiedReviewCount());
        assertEquals(0L, stats.organicReviewCount());
        assertEquals(0L, stats.helpfulVoteCount());
    }
}
