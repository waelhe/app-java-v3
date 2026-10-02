package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewModerationStatus;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.reviews.ReviewsService;
import com.marketplace.shared.api.ReviewLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * W1 (yelp-level plan §4.5): the reviews module's implementation of the
 * {@link ReviewLookupPort} cross-module contract (the ReviewStatsAdapter
 * pattern verbatim). The two lookup gates read the repository directly
 * (soft-deleted rows are already excluded by the entity's filter); the
 * moderation hide delegates to the owning service so the state machine,
 * the events and the admin gate all stay in one place — joined to the
 * caller's transaction (the community resolve's one-unit-of-work rule:
 * the hide and the report's close commit atomically).
 */
@Component
@Transactional(readOnly = true)
public class ReviewsLookupAdapter implements ReviewLookupPort {

    private final ReviewRepository reviewRepository;
    private final ReviewsService reviewsService;

    public ReviewsLookupAdapter(ReviewRepository reviewRepository, ReviewsService reviewsService) {
        this.reviewRepository = reviewRepository;
        this.reviewsService = reviewsService;
    }

    @Override
    public Optional<UUID> findVisibleAuthorId(UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .filter(review -> review.getModerationStatus() == ReviewModerationStatus.PUBLISHED)
                .map(Review::getReviewerId);
    }

    @Override
    public Optional<UUID> findAuthorId(UUID reviewId) {
        return reviewRepository.findById(reviewId)
                .map(Review::getReviewerId);
    }

    @Override
    @Transactional
    public Optional<UUID> hideAsModerator(UUID reviewId) {
        return reviewsService.hideByModerator(reviewId);
    }
}
