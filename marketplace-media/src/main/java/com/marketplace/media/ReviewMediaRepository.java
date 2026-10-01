package com.marketplace.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReviewMediaRepository extends JpaRepository<ReviewMedia, UUID> {

    List<ReviewMedia> findByReviewIdAndStatusOrderByPositionAsc(UUID reviewId, MediaAssetStatus status);

    long countByReviewId(UUID reviewId);

    /**
     * The highest allocated display position for the review — over ALL rows,
     * soft-deleted included (greptile W1 r10, adopted from the root). Position
     * is an allocation sequence, not a live count: after a soft deletion the
     * remaining rows keep their positions, so {@code countByReviewId()+1} can
     * re-issue a position a remaining row already holds (delete position 1 of
     * three — the count says 2, position 3 is taken). The maximum — which no
     * deletion lowers — is the honest "next free" answer. No {@code @Where}
     * soft-delete filter exists on this entity, so JPQL sees deleted rows too.
     */
    @Query("select coalesce(max(m.position), 0) from ReviewMedia m where m.reviewId = :reviewId")
    int findMaxPositionByReviewId(@Param("reviewId") UUID reviewId);

    /**
     * Serializes display-position allocation per review — the exact
     * advisory-transaction-lock shape the listing media repository
     * measured (CodeRabbit #241): count+1 does not serialize concurrent
     * uploads; the lock is held until the surrounding transaction commits
     * and {@code hashtextextended} maps the review UUID text to one bigint
     * key (PostgreSQL 13+, the repo's PG 17/18 baseline).
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:reviewId, 0))", nativeQuery = true)
    void lockPositionAllocation(@Param("reviewId") String reviewId);
}
