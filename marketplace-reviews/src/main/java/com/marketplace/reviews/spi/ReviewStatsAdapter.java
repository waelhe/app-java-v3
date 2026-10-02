package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
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

    @Override
    public Optional<ReviewStats> findStatsByProviderId(UUID providerId) {
        return reviewRepository.getStatsByProviderId(providerId);
    }

    /**
     * W1 (§4.4): the GENERAL (organic) aggregate — same recompute discipline,
     * origin filter flipped (PUBLISHED organic forward reviews only).
     */
    @Override
    public Optional<ReviewStats> findGeneralStatsByProviderId(UUID providerId) {
        return reviewRepository.getGeneralStatsByProviderId(providerId);
    }

    /** W1 (§4.4): the general-aggregate resolve-by-review form (the listener's second channel). */
    @Override
    public Optional<ReviewStats> findGeneralStatsByReviewId(UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .flatMap(review -> reviewRepository.getGeneralStatsByProviderId(review.getProviderId()));
    }

    /**
     * W1 (§4.4): the stored-average listener's resolve seam — the review's
     * provider USER id ({@code reviews.provider_id}, the A1 space), present
     * for every live row regardless of its moderation status (a hidden or
     * pending review still resolves its provider — the recompute then
     * simply excludes it, possibly clearing the stored value).
     */
    @Override
    public Optional<UUID> findProviderUserIdByReviewId(UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .map(Review::getProviderId);
    }
}
