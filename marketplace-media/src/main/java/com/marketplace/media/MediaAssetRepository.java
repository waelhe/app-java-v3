package com.marketplace.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, UUID> {

    List<MediaAsset> findByListingIdAndStatusOrderByPositionAsc(UUID listingId, MediaAssetStatus status);

    List<MediaAsset> findByListingIdOrderByPositionAsc(UUID listingId);

    /**
     * I7 Phase 2 (account-pseudonymization-plan §5-ج): every live asset the
     * user owns ({@code provider_id} is a user id — the A1 measured fact),
     * in creation order for a deterministic export — backs
     * {@code MediaExportAdapter}.
     */
    List<MediaAsset> findAllByProviderIdOrderByCreatedAtAsc(UUID providerId);

    long countByListingId(UUID listingId);

    /**
     * Serializes display-position allocation per listing (CodeRabbit #241):
     * {@code countByListingId(listingId) + 1} inside a transaction does not
     * stop two concurrent uploads from reading the same count and persisting
     * the same position. This advisory transaction lock (held until the
     * surrounding transaction commits, released automatically on any exit)
     * makes the count-then-save sequence exclusive per listing.
     * {@code hashtextextended} maps the listing UUID text to one bigint lock
     * key (PostgreSQL 13+, the repo's PG 17/18 baseline).
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:listingId, 0))", nativeQuery = true)
    void lockListingPositionAllocation(@Param("listingId") String listingId);
}
