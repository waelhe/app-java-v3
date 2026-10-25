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
 * the hide and the report's close commit atomically). The automatic
 * engine's hide (row 3) delegates the same way to the service's engine
 * path: one physics, two authority paths (the manual one keeps its ADMIN
 * gate; the engine's carries the machine's own authority).
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

    /**
     * B-19/C.11 (row 3 — the engine's narrow internal path): the automatic
     * engine's hide — the same physics as the manual hide above, delegated
     * to the owning service's engine method so the state machine, the
     * events and the cache eviction stay in one place. Joined to the
     * caller's transaction: the report's creation, the review's hide and
     * every resolve commit together (the one-unit-of-work rule) — the
     * ordinary reporter rides the machine's authority, not his own.
     */
    @Override
    @Transactional
    public Optional<UUID> hideAsModerationEngine(UUID reviewId) {
        return reviewsService.hideByModerationEngine(reviewId);
    }
}
