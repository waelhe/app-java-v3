package com.marketplace.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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
    List<MediaAsset> findAllByProviderIdOrderByCreatedAtAscIdAsc(UUID providerId);

    long countByListingId(UUID listingId);

    /**
     * L38 (realestate systems plan — completeness score): the count of one
     * listing's assets in a given status — {@code MediaLookupAdapter} calls
     * it with {@code UPLOADED} (the port's storage-verified-only contract).
     * Derived like its sibling above, so the shared {@code @SoftDelete}
     * excludes purged rows from both counts.
     */
    long countByListingIdAndStatus(UUID listingId, MediaAssetStatus status);

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

    // ---------- L48: the post target (gap #2 — post images) ----------

    /**
     * L48: the feed's one grouped media read — every asset of the given
     * posts in a given status, ordered (postId, position) so the consumer
     * groups without a second assumption. Derived like the listing
     * siblings, so the shared {@code @SoftDelete} excludes purged rows;
     * {@code MediaLookupAdapter} calls it with {@code UPLOADED} (the
     * port's storage-verified-only contract) and the V76 partial index
     * {@code idx_media_assets_post_feed} serves exactly this scan.
     */
    List<MediaAsset> findByPostIdInAndStatusOrderByPostIdAscPositionAsc(
            Collection<UUID> postIds, MediaAssetStatus status);

    /** L48: the post twin of {@link #countByListingId(UUID)}. */
    long countByPostId(UUID postId);

    /**
     * The highest allocated display position for the listing — LIVE rows
     * (the shared {@code @SoftDelete} filters purged ones out of every
     * query), which no deletion lowers below a remaining row's own value
     * (greptile W1 r10, adopted from the root — the same defect class the
     * review channel's {@code ReviewMediaRepository.findMaxPositionByReviewId}
     * fixes): {@code countByListingId()+1} can re-issue a position a
     * remaining live row already holds, while {@code max(live)+1} cannot.
     */
    @Query("select coalesce(max(a.position), 0) from MediaAsset a where a.listingId = :listingId")
    int findMaxPositionByListingId(@Param("listingId") UUID listingId);

    /** The post twin of {@link #findMaxPositionByListingId(UUID)}. */
    @Query("select coalesce(max(a.position), 0) from MediaAsset a where a.postId = :postId")
    int findMaxPositionByPostId(@Param("postId") UUID postId);

    /**
     * L48: the post twin of {@link #lockListingPositionAllocation(String)} —
     * the same advisory transaction lock discipline (CodeRabbit #241),
     * serialized per POST id. The key space is shared with the listing lock
     * (both {@code hashtextextended(uuid, 0)}): a cross-family hash
     * collision can only OVER-serialize the two allocations for an instant,
     * never under-serialize them — the lock is the serialization, so
     * correctness is unaffected either way (the same stance as one shared
     * advisory namespace across the whole table).
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:postId, 0))", nativeQuery = true)
    void lockPostPositionAllocation(@Param("postId") String postId);
}
