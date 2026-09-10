package com.marketplace.reviews.spi;

import com.marketplace.reviews.Review;
import com.marketplace.reviews.ReviewDirection;
import com.marketplace.reviews.ReviewRepository;
import com.marketplace.shared.api.ReviewExportEntry;
import com.marketplace.shared.api.ReviewExportPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): the reviews module's implementation of the
 * {@link ReviewExportPort} cross-module contract (the
 * {@code ReviewStatsAdapter} house pattern). A read-only delegation with
 * the plan's provenance mapping: the reviews the requester authored (both
 * directions — "ما ألّفه بنفسه"), the counterparty as one opaque UUID in
 * the direction's own id space (the Review entity's no-mixed-spaces rule),
 * and the counterparty's {@code reply} deliberately absent (it is his
 * counterpart's authored content on a shared record).
 */
@Component
@Transactional(readOnly = true)
public class ReviewExportAdapter implements ReviewExportPort {

    private final ReviewRepository reviewRepository;

    public ReviewExportAdapter(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Override
    public List<ReviewExportEntry> exportForAuthor(UUID userId) {
        return reviewRepository.findAllByReviewerIdOrderByCreatedAtAscIdAsc(userId)
                .stream()
                .map(ReviewExportAdapter::toEntry)
                .toList();
    }

    private static ReviewExportEntry toEntry(Review review) {
        boolean forward = review.getDirection() == ReviewDirection.CONSUMER_TO_PROVIDER;
        // Counterparty minimality, one field per direction — both users.id
        // space (the physically measured fact: V6's FK on provider_id, and
        // the write path stores the booking's provider user id in both
        // directions): the reviewed provider on a forward review, the
        // reviewed consumer on a reverse one — never both, never mixed.
        UUID reviewedProviderUserId = forward ? review.getProviderId() : null;
        UUID reviewedConsumerUserId = forward ? null : review.getRevieweeId();
        return new ReviewExportEntry(
                review.getId(),
                review.getBookingId(),
                review.getDirection().name(),
                review.getRating(),
                review.getComment(),
                reviewedProviderUserId,
                reviewedConsumerUserId,
                review.getCreatedAt(),
                review.getUpdatedAt());
    }
}
