package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewerOriginCount;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.reviews.ReviewVoteRepository;
import com.marketplace.shared.api.ReviewerStats;
import com.marketplace.shared.api.ReviewerStatsPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * W4 (yelp-level plan §5 — G28/G29): the reviews module's implementation of
 * the {@link ReviewerStatsPort} cross-module contract (the
 * {@code ReviewStatsAdapter} pattern verbatim): the origin-split published
 * counters and the cumulative helpful-vote total for one reviewer, always
 * recomputed from the tables — never a stored aggregate — so the page can
 * never drift from the source of truth.
 *
 * <p>An unknown origin value never reaches the arithmetic: the grouping is
 * over the column the V85 DB CHECK pins to exactly the two constants
 * {@code Review.ORIGIN_BOOKING}/{@code Review.ORIGIN_ORGANIC}, so a third
 * value can only appear after a widening migration — and would be visibly
 * absent from the page's counters rather than silently mis-bucketed.
 */
@Component
@Transactional(readOnly = true)
public class ReviewerStatsAdapter implements ReviewerStatsPort {

    private final ReviewRepository reviewRepository;
    private final ReviewVoteRepository reviewVoteRepository;

    public ReviewerStatsAdapter(ReviewRepository reviewRepository,
                                ReviewVoteRepository reviewVoteRepository) {
        this.reviewRepository = reviewRepository;
        this.reviewVoteRepository = reviewVoteRepository;
    }

    @Override
    public ReviewerStats findReviewerStats(UUID reviewerId) {
        long verified = 0;
        long organic = 0;
        for (ReviewerOriginCount row : reviewRepository.countPublishedByOriginForReviewer(reviewerId)) {
            if (Review.ORIGIN_BOOKING.equals(row.origin())) {
                verified = row.count();
            } else if (Review.ORIGIN_ORGANIC.equals(row.origin())) {
                organic = row.count();
            }
        }
        long helpful = reviewVoteRepository.countByReviewerId(reviewerId);
        return new ReviewerStats(verified, organic, helpful);
    }
}
