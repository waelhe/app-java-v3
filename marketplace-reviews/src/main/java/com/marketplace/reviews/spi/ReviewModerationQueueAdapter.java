package com.marketplace.reviews.spi;

import com.marketplace.reviews.ReviewModerationStatus;
import com.marketplace.reviews.ReviewsService;
import com.marketplace.shared.api.ReviewModerationQueueEntry;
import com.marketplace.shared.api.ReviewModerationQueuePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The reviews module's implementation of the
 * {@link ReviewModerationQueuePort} cross-module contract — the manager
 * console's moderation board reads and moves the PENDING_REVIEW inbox
 * through this seam, never through a module dependency (the
 * {@code ReviewsLookupAdapter} house pattern verbatim: the interface
 * lives in shared-api, the data owner implements it, the consumer
 * injects it).
 *
 * <p><b>No parallel write path:</b> both verdicts are the SAME
 * {@code approve}/{@code reject} service calls the module's own REST
 * admin controller ({@code ReviewModerationAdminController}) makes —
 * the adapter is pure delegation, so the service's own transaction,
 * observation ({@code review.moderate.approve}/{@code
 * review.moderate.reject}), and method-security contracts apply
 * unchanged to the web lever.</p>
 */
@Component
public class ReviewModerationQueueAdapter implements ReviewModerationQueuePort {

    private final ReviewsService reviewsService;

    public ReviewModerationQueueAdapter(ReviewsService reviewsService) {
        this.reviewsService = reviewsService;
    }

    @Override
    public List<ReviewModerationQueueEntry> pendingModerationQueue(int limit) {
        return reviewsService
                .moderationQueue(ReviewModerationStatus.PENDING_REVIEW, PageRequest.of(0, limit))
                .map(item -> new ReviewModerationQueueEntry(
                        item.id(),
                        item.reviewerId(),
                        item.providerId(),
                        item.rating(),
                        item.comment(),
                        item.flags()))
                .getContent();
    }

    @Override
    public void approveReview(UUID reviewId) {
        reviewsService.approve(reviewId);
    }

    @Override
    public void rejectReview(UUID reviewId) {
        reviewsService.reject(reviewId);
    }
}
