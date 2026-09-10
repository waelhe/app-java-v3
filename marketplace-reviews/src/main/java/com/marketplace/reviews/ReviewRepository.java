package com.marketplace.reviews;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID>, RevisionRepository<Review, UUID, Integer> {

    /**
     * I8: the provider's public review surface keeps its historical
     * meaning — reviews ABOUT the provider — which after the two-way review
     * means the forward direction only (reverse reviews belong to their
     * author, not to the reviewed surface).
     */
    Page<Review> findByProviderIdAndDirection(UUID providerId, ReviewDirection direction, Pageable pageable);

    Page<Review> findByReviewerId(UUID reviewerId, Pageable pageable);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): every live review
     * the user authored (both directions), in creation order for a
     * deterministic export — backs {@code ReviewExportAdapter}.
     */
    List<Review> findAllByReviewerIdOrderByCreatedAtAsc(UUID reviewerId);

    /** I8: the reverse reviews about one reviewed consumer (the trust view). */
    Page<Review> findByRevieweeIdAndDirection(UUID revieweeId, ReviewDirection direction, Pageable pageable);

    /**
     * I8: per-direction uniqueness — a booking carries at most one
     * consumer review AND at most one provider review (two distinct
     * entries per booking, one per direction).
     */
    boolean existsByBookingIdAndDirection(UUID bookingId, ReviewDirection direction);

    /**
     * L21: the recomputed rating statistics for one provider — always an
     * aggregate over the live (non-soft-deleted) reviews so the stored
     * average cannot drift from the source of truth.
     *
     * <p>I8: FORWARD reviews only — a provider-authored rating of the
     * consumer must never pollute the provider's own average.
     */
    @Query("""
            select new com.marketplace.shared.api.ReviewStats(
                r.providerId, avg(r.rating), count(r))
            from Review r
            where r.providerId = :providerId
              and r.direction = com.marketplace.reviews.ReviewDirection.CONSUMER_TO_PROVIDER
            group by r.providerId
            """)
    Optional<com.marketplace.shared.api.ReviewStats> getStatsByProviderId(UUID providerId);
}