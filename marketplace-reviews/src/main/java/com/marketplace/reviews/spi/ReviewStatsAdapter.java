package com.marketplace.reviews.spi;

import com.marketplace.reviews.ReviewRepository;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.ReviewStatsPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * L21 (feature-expansion roadmap §5): the reviews module's implementation of
 * the {@link ReviewStatsPort} cross-module contract. The stats are always
 * recomputed from the reviews table — the provider module's event listener
 * stores the aggregate, this side owns the aggregate's source.
 */
@Component
@Transactional(readOnly = true)
public class ReviewStatsAdapter implements ReviewStatsPort {

    private final ReviewRepository reviewRepository;

    public ReviewStatsAdapter(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Override
    public Optional<ReviewStats> findStatsByReviewId(UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .flatMap(review -> reviewRepository.getStatsByProviderId(review.getProviderId()));
    }
}
