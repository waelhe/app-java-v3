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
