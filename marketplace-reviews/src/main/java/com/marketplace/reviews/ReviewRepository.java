package com.marketplace.reviews;

import com.marketplace.shared.api.RatingDistribution;
import com.marketplace.shared.api.ReviewStats;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.repository.query.Param;

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
     * Greptile W1 r3-frontend (adopted): the derived ordering is explicit —
     * {@code createdAt DESC, id DESC}, the D-N5 complete key — because the
     * published-reviews port promises newest-first and an unordered page
     * both shuffles rows and shifts them across page boundaries.
     */
    Page<Review> findByProviderIdAndDirectionAndModerationStatusOrderByCreatedAtDescIdDesc(
            UUID providerId, ReviewDirection direction, ReviewModerationStatus moderationStatus, Pageable pageable);

    /**
     * W2 (greptile round 2, adopted from the root): the provider's
     * PUBLISHED surface scoped to ONE origin — the leading-rows read the
     * public page's JSON-LD sample composes. The mode-driven aggregate
     * describes the booking-origin population in VERIFIED_ONLY/HYBRID, so
     * the sample must draw from that population directly — a filter of the
     * caller's requested page can produce an empty sample (an
     * organic-only page after a mode switch) while the aggregate beside it
     * reports verified reviews: markup that disagrees with its own page.
     * Same gates and same complete ordering key as the unscoped read.
     */
    Page<Review> findByProviderIdAndDirectionAndModerationStatusAndOriginOrderByCreatedAtDescIdDesc(
            UUID providerId, ReviewDirection direction, ReviewModerationStatus moderationStatus,
            String origin, Pageable pageable);

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
     * W4 (yelp-level plan §5 — G28/G29): the public reviewer page's split
     * counters — one reviewer's PUBLISHED reviews grouped by the origin
     * column (V85's provenance). The same population
     * {@link #countPublishedByReviewerIds} counts un-split, so the two rows
     * this returns sum to the per-review {@code reviewerReviewCount} block
     * the provider page's rows already carry (one measurement discipline,
     * two projections). An origin with no rows is simply absent from the
     * list — the caller reads it as zero.
     */
    @Query("""
            select new com.marketplace.reviews.ReviewerOriginCount(r.origin, count(r))
            from Review r
            where r.reviewerId = :reviewerId
              and r.moderationStatus = com.marketplace.reviews.ReviewModerationStatus.PUBLISHED
            group by r.origin
            """)
    List<ReviewerOriginCount> countPublishedByOriginForReviewer(UUID reviewerId);

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

    /**
     * W2 (yelp-level plan §5 — the business page): the VERIFIED rating
     * histogram — the same population as {@link #getStatsByProviderId}
     * (forward, BOOKING, PUBLISHED, live), grouped by star value. Sparse
     * by construction (group-by yields only present ratings); the port
     * adapter fills the zero buckets through
     * {@link RatingDistribution#of(UUID, List)}.
     */
    @Query("""
            select new com.marketplace.shared.api.RatingDistribution$RatingBucket(
                r.rating, count(r))
            from Review r
            where r.providerId = :providerId
              and r.direction = com.marketplace.reviews.ReviewDirection.CONSUMER_TO_PROVIDER
              and r.origin = 'BOOKING'
              and r.moderationStatus = com.marketplace.reviews.ReviewModerationStatus.PUBLISHED
            group by r.rating
            order by r.rating asc
            """)
    List<RatingDistribution.RatingBucket> getRatingDistributionByProviderId(UUID providerId);

    /**
     * W2 (§5): the GENERAL (organic) histogram — the same population as
     * {@link #getGeneralStatsByProviderId}, grouped by star value; the
     * adapter fills the zero buckets.
     */
    @Query("""
            select new com.marketplace.shared.api.RatingDistribution$RatingBucket(
                r.rating, count(r))
            from Review r
            where r.providerId = :providerId
              and r.direction = com.marketplace.reviews.ReviewDirection.CONSUMER_TO_PROVIDER
              and r.origin = 'ORGANIC'
              and r.moderationStatus = com.marketplace.reviews.ReviewModerationStatus.PUBLISHED
            group by r.rating
            order by r.rating asc
            """)
    List<RatingDistribution.RatingBucket> getGeneralRatingDistributionByProviderId(UUID providerId);

    /**
     * W1 §4.5 (greptile W1 r9, adopted from the root): serializes the
     * per-reviewer admission decisions — the daily-cap count, the 1×1
     * organic uniqueness check, and the first-N moderation count are all
     * count-then-insert, so two concurrent submissions from the same
     * reviewer would each read the same pre-insert state and both pass.
     * The advisory-transaction-lock shape is the media repository's
     * measured #241 pattern (held until the surrounding transaction
     * commits, released on any exit). Seed 7 keeps this key space disjoint
     * from the media locks' seed 0 — the two families never share a lock
     * key even on a UUID-text hash collision.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:reviewerId, 7))", nativeQuery = true)
    void lockReviewerDecisions(@Param("reviewerId") String reviewerId);
}