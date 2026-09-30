package com.marketplace.reviews;

import com.marketplace.shared.api.ReviewStats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID>, RevisionRepository<Review, UUID, Integer> {

    /**
     * I8 + W1 (§4.5): the provider's public review surface keeps its
     * historical meaning — reviews ABOUT the provider (forward direction
     * only) — now PUBLISHED-gated: a pending or moderator-hidden review is
     * absent from the public list (the plan's named visibility path 1).
     */
    Page<Review> findByProviderIdAndDirectionAndModerationStatus(
            UUID providerId, ReviewDirection direction, ReviewModerationStatus moderationStatus, Pageable pageable);

    /** The reviewer's own surface (all moderation states — the owner view). */
    Page<Review> findByReviewerId(UUID reviewerId, Pageable pageable);

    /** The public reviewer-profile surface: PUBLISHED only (§4.5). */
    Page<Review> findByReviewerIdAndModerationStatus(
            UUID reviewerId, ReviewModerationStatus moderationStatus, Pageable pageable);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): every live review
     * the user authored (both directions), in creation order for a
     * deterministic export — backs {@code ReviewExportAdapter}.
     */
    List<Review> findAllByReviewerIdOrderByCreatedAtAscIdAsc(UUID reviewerId);

    /** I8 + W1: the reverse reviews about one reviewed consumer — PUBLISHED-gated. */
    Page<Review> findByRevieweeIdAndDirectionAndModerationStatus(
            UUID revieweeId, ReviewDirection direction, ReviewModerationStatus moderationStatus, Pageable pageable);

    /**
     * I8: per-direction uniqueness — a booking carries at most one
     * consumer review AND at most one provider review (two distinct
     * entries per booking, one per direction).
     */
    boolean existsByBookingIdAndDirection(UUID bookingId, ReviewDirection direction);

    /** W1 §4.1 (G10): organic 1×1 uniqueness — the explicit 409's fact; the V72 index is the backstop. */
    boolean existsByReviewerIdAndProviderIdAndOrigin(UUID reviewerId, UUID providerId, String origin);

    /** W1 §4.5: the account's organic-review count ever — the first-three queue rule's fact. */
    long countByReviewerIdAndOrigin(UUID reviewerId, String origin);

    /** W1 §4.5: the daily-cap count — organic reviews inside the rolling window. */
    long countByReviewerIdAndOriginAndCreatedAtGreaterThanEqual(UUID reviewerId, String origin, Instant since);

    /** W1 §4.5: the burst signal — organic reviews on one provider inside the window. */
    long countByProviderIdAndOriginAndCreatedAtGreaterThanEqual(UUID providerId, String origin, Instant since);

    /**
     * W1 §4.5: the text-similarity signal — the reviewer already wrote the
     * same normalized text on ANOTHER live review ({@code excludeId} keeps
     * the just-saved row out of its own match).
     */
    @Query("""
            select count(r) > 0 from Review r
            where r.reviewerId = :reviewerId
              and r.origin = 'ORGANIC'
              and r.id <> :excludeId
              and r.comment is not null
              and lower(trim(r.comment)) = lower(trim(:comment))
            """)
    boolean existsOrganicWithNormalizedComment(UUID reviewerId, String comment, UUID excludeId);

    /** W1 §4.5: the moderation queue drain — status-filtered; the caller applies the FIFO sort. */
    Page<Review> findByModerationStatus(ReviewModerationStatus moderationStatus, Pageable pageable);

    /**
     * W1 §4.4: the reviewer-identity block's batch counter — PUBLISHED
     * reviews authored per reviewer, one grouped query for a whole page
     * (the batch-resolution rule; the public activity count a profile
     * badge means).
     */
    @Query("""
            select new com.marketplace.reviews.ReviewerReviewCount(r.reviewerId, count(r))
            from Review r
            where r.reviewerId in :reviewerIds
              and r.moderationStatus = com.marketplace.reviews.ReviewModerationStatus.PUBLISHED
            group by r.reviewerId
            """)
    List<ReviewerReviewCount> countPublishedByReviewerIds(Collection<UUID> reviewerIds);

    /**
     * L21: the recomputed rating statistics for one provider — always an
     * aggregate over the live (non-soft-deleted) reviews so the stored
     * average cannot drift from the source of truth.
     *
     * <p><b>W1 semantics (§4.4):</b> FORWARD, {@code origin='BOOKING'},
     * {@code PUBLISHED}, live reviews only — the verified aggregate. On
     * all-BOOKING legacy data the two new filters are no-ops: this stays
     * byte-identical to the pre-W1 aggregate (the plan's backward-compat
     * rule for the stored {@code rating_average}).
     */
    @Query("""
            select new com.marketplace.shared.api.ReviewStats(
                r.providerId, avg(r.rating), count(r))
            from Review r
            where r.providerId = :providerId
              and r.direction = com.marketplace.reviews.ReviewDirection.CONSUMER_TO_PROVIDER
              and r.origin = 'BOOKING'
              and r.moderationStatus = com.marketplace.reviews.ReviewModerationStatus.PUBLISHED
            group by r.providerId
            """)
    Optional<ReviewStats> getStatsByProviderId(UUID providerId);

    /**
     * W1 (§4.4): the GENERAL (organic) aggregate — same shape, origin
     * filter flipped. Feeds the second stored pair
     * ({@code rating_general_average}/{@code rating_general_count}) and the
     * HYBRID display's second badge.
     */
    @Query("""
            select new com.marketplace.shared.api.ReviewStats(
                r.providerId, avg(r.rating), count(r))
            from Review r
            where r.providerId = :providerId
              and r.direction = com.marketplace.reviews.ReviewDirection.CONSUMER_TO_PROVIDER
              and r.origin = 'ORGANIC'
              and r.moderationStatus = com.marketplace.reviews.ReviewModerationStatus.PUBLISHED
            group by r.providerId
            """)
    Optional<ReviewStats> getGeneralStatsByProviderId(UUID providerId);
}