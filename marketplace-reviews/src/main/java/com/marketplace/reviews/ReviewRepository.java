package com.marketplace.reviews;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID>, RevisionRepository<Review, UUID, Integer> {

    Page<Review> findByProviderId(UUID providerId, Pageable pageable);

    Page<Review> findByReviewerId(UUID reviewerId, Pageable pageable);

    boolean existsByBookingId(UUID bookingId);

    /**
     * L21: the recomputed rating statistics for one provider — always an
     * aggregate over the live (non-soft-deleted) reviews so the stored
     * average cannot drift from the source of truth.
     */
    @Query("""
            select new com.marketplace.shared.api.ReviewStats(
                r.providerId, avg(r.rating), count(r))
            from Review r
            where r.providerId = :providerId
            group by r.providerId
            """)
    Optional<com.marketplace.shared.api.ReviewStats> getStatsByProviderId(UUID providerId);
}