package com.marketplace.realestate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface PropertyDetailsRepository
        extends JpaRepository<PropertyDetails, UUID>,
        JpaSpecificationExecutor<PropertyDetails>,
        RevisionRepository<PropertyDetails, UUID, Integer> {

    /** The 1:1 lookup — listing_id is UNIQUE (V48). */
    Optional<PropertyDetails> findByListingId(UUID listingId);

    boolean existsByListingId(UUID listingId);

    /** The batch embed lookup for page aggregation (one IN query). */
    List<PropertyDetails> findByListingIdIn(Set<UUID> listingIds);

    // ------------------------------------------------------------------
    // P1 (postgis integration plan §D-P2/D-P4): the radius native queries.
    // The searchFullText house pattern, literally: nativeQuery on this
    // module's own repository, the soft-delete predicate explicit, and the
    // partial-index predicate (V50) mirrored word for word so the planner
    // can match index to query. ST_DWithin is the official radius predicate
    // (it uses the spatial index; ST_Distance/ST_Buffer filtering does
    // not). The center expression is built exactly like the indexed row
    // expression — ST_SetSRID(ST_MakePoint(longitude, latitude),
    // 4326)::geography — over the IMMUTABLE geography(geometry) cast.
    // The adapter guards the empty-set cases before any of these runs
    // (an empty IN () is invalid SQL, not a semantics question).
    // ------------------------------------------------------------------

    /**
     * The listing ids whose coordinates lie within the radius (the set
     * form — the D-E6 set-restriction integration).
     */
    @Query(value = """
            SELECT listing_id FROM property_details
            WHERE is_deleted = FALSE
              AND latitude IS NOT NULL AND longitude IS NOT NULL
              AND ST_DWithin(
                    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                    :radiusMeters)
            """,
            nativeQuery = true)
    Set<UUID> findListingIdsWithinRadius(@Param("latitude") BigDecimal latitude,
                                         @Param("longitude") BigDecimal longitude,
                                         @Param("radiusMeters") long radiusMeters);

    /**
     * The window path's set form: within the radius AND written by one of
     * the given providers (the availability whitelist — never empty, the
     * adapter guarded it).
     */
    @Query(value = """
            SELECT listing_id FROM property_details
            WHERE is_deleted = FALSE
              AND latitude IS NOT NULL AND longitude IS NOT NULL
              AND ST_DWithin(
                    ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                    :radiusMeters)
              AND provider_id IN (:providerIds)
            """,
            nativeQuery = true)
    Set<UUID> findListingIdsWithinRadiusRestricted(@Param("latitude") BigDecimal latitude,
                                                   @Param("longitude") BigDecimal longitude,
                                                   @Param("radiusMeters") long radiusMeters,
                                                   @Param("providerIds") Set<UUID> providerIds);

    /**
     * The distance-ordered paged form: the within-radius matches restricted
     * to the catalog-resolved ACTIVE set, ordered nearest-first by
     * ST_Distance with the listing-id ASC tiebreak (deterministic offset
     * pagination). The ORDER BY is baked — the adapter delivers an
     * UNSORTED pageable (LIMIT/OFFSET only); the count query carries the
     * same predicates so the page total is the radius flow's own.
     */
    @Query(value = """
            SELECT pd.* FROM property_details pd
            WHERE pd.is_deleted = FALSE
              AND pd.latitude IS NOT NULL AND pd.longitude IS NOT NULL
              AND ST_DWithin(
                    ST_SetSRID(ST_MakePoint(pd.longitude, pd.latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                    :radiusMeters)
              AND pd.listing_id IN (:activeListingIds)
            ORDER BY ST_Distance(
                    ST_SetSRID(ST_MakePoint(pd.longitude, pd.latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography) ASC,
                     pd.listing_id ASC
            """,
            countQuery = """
                    SELECT COUNT(*) FROM property_details pd
                    WHERE pd.is_deleted = FALSE
                      AND pd.latitude IS NOT NULL AND pd.longitude IS NOT NULL
                      AND ST_DWithin(
                            ST_SetSRID(ST_MakePoint(pd.longitude, pd.latitude), 4326)::geography,
                            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                            :radiusMeters)
                      AND pd.listing_id IN (:activeListingIds)
                    """,
            nativeQuery = true)
    Page<PropertyDetails> findWithinRadiusPaged(@Param("latitude") BigDecimal latitude,
                                                @Param("longitude") BigDecimal longitude,
                                                @Param("radiusMeters") long radiusMeters,
                                                @Param("activeListingIds") Set<UUID> activeListingIds,
                                                Pageable pageable);

    /**
     * The provider-restricted paged form (window + distance sort): the
     * same predicates plus the provider whitelist.
     */
    @Query(value = """
            SELECT pd.* FROM property_details pd
            WHERE pd.is_deleted = FALSE
              AND pd.latitude IS NOT NULL AND pd.longitude IS NOT NULL
              AND ST_DWithin(
                    ST_SetSRID(ST_MakePoint(pd.longitude, pd.latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                    :radiusMeters)
              AND pd.listing_id IN (:activeListingIds)
              AND pd.provider_id IN (:providerIds)
            ORDER BY ST_Distance(
                    ST_SetSRID(ST_MakePoint(pd.longitude, pd.latitude), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography) ASC,
                     pd.listing_id ASC
            """,
            countQuery = """
                    SELECT COUNT(*) FROM property_details pd
                    WHERE pd.is_deleted = FALSE
                      AND pd.latitude IS NOT NULL AND pd.longitude IS NOT NULL
                      AND ST_DWithin(
                            ST_SetSRID(ST_MakePoint(pd.longitude, pd.latitude), 4326)::geography,
                            ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
                            :radiusMeters)
                      AND pd.listing_id IN (:activeListingIds)
                      AND pd.provider_id IN (:providerIds)
                    """,
            nativeQuery = true)
    Page<PropertyDetails> findWithinRadiusPagedRestricted(@Param("latitude") BigDecimal latitude,
                                                          @Param("longitude") BigDecimal longitude,
                                                          @Param("radiusMeters") long radiusMeters,
                                                          @Param("activeListingIds") Set<UUID> activeListingIds,
                                                          @Param("providerIds") Set<UUID> providerIds,
                                                          Pageable pageable);
}
