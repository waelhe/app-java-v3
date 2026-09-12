package com.marketplace.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ProviderListingRepository extends JpaRepository<ProviderListing, UUID>, JpaSpecificationExecutor<ProviderListing>, RevisionRepository<ProviderListing, UUID, Integer> {

    Page<ProviderListing> findByProviderId(UUID providerId, Pageable pageable);

    Page<ProviderListing> findByCategoryAndStatus(String category, ListingStatus status, Pageable pageable);

    Page<ProviderListing> findByStatus(ListingStatus status, Pageable pageable);

    /**
     * L33: the expiry job's scan — ACTIVE listings whose publication
     * window passed (strictly before: the boundary instant stays ACTIVE
     * until the next tick). Deterministic id order for stable paging.
     */
    Page<ProviderListing> findByStatusAndExpiresAtBefore(ListingStatus status,
                                                          java.time.Instant expiresAt,
                                                          Pageable pageable);

    /**
     * L32 (realestate systems plan): the ACTIVE listing id set — the
     * restriction the area-sorted search flow passes into the realestate
     * filter port. JPQL (the soft-delete filter applies automatically
     * through the entity's @SoftDelete).
     */
    @Query("select l.id from ProviderListing l where l.status = ?1")
    Set<UUID> findIdsByStatus(ListingStatus status);

    /**
     * Full-text search using PostgreSQL tsvector with GIN index.
     * Searches title and description columns.
     * Matches the GIN index defined in V9__search_index.sql.
     *
     * <p>Uses the official {@code websearch_to_tsquery} — the PostgreSQL function
     * designed for raw user input: "simple unformatted text is a valid query"
     * and arbitrary special characters never raise a tsquery syntax error
     * (PostgreSQL 18 reference, Functions › Text Search › Parsing Queries,
     * example: {@code websearch_to_tsquery('english', '""" )( dummy \ query <->')}
     * parses to {@code 'dummi' & 'queri'}). The previous {@code to_tsquery} form
     * received mangled user input and raised SQL exceptions (HTTP 500) for any
     * input containing quotes, parentheses or a leading dash. Users also gain
     * the officially supported web-search operators: {@code "quoted phrase"},
     * {@code OR}, and {@code -exclusion}.
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                  @@ websearch_to_tsquery('simple', :query)
            ORDER BY ts_rank(
                to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,'')),
                websearch_to_tsquery('simple', :query)
            ) DESC
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                          @@ websearch_to_tsquery('simple', :query)
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchFullText(@Param("query") String query, Pageable pageable);

    /**
     * Typo-tolerant fallback search using the official pg_trgm extension
     * (installed by V34): {@code word_similarity} compares the query's
     * trigram set against every continuous extent of the indexed text, so
     * a one-edit typo ("gardn" → "garden") still scores high similarity.
     * Ranked by descending word similarity. Uses the GIN trigram index
     * {@code idx_listing_search_trgm} (same expression and partial
     * predicate as the FTS index — V34).
     *
     * <p>Operator {@code query <% text}: true when
     * {@code word_similarity(query, text) >= pg_trgm.word_similarity_threshold}
     * — the framework default (0.6), deliberately not overridden: threshold
     * tuning is a measurement-backed decision, not a code default.
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
            ORDER BY word_similarity(:query, coalesce(title,'') || ' ' || coalesce(description,'')) DESC
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchSimilar(@Param("query") String query, Pageable pageable);

    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false
              AND status = 'ACTIVE'
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false
                      AND status = 'ACTIVE'
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchByCriteria(@Param("category") String category,
                                           @Param("minPrice") Long minPrice,
                                           @Param("maxPrice") Long maxPrice,
                                           @Param("guests") Integer guests,
                                           Pageable pageable);

    // L27 (feature-expansion roadmap §5) — window-restricted variants. The
    // provider-id whitelist is the server-derived availability answer (see
    // AvailabilityLookupPort); the caller guarantees a NON-EMPTY collection —
    // an empty whitelist short-circuits to an honest empty page before any
    // query. ORDER BY id is the deterministic total order offset pagination
    // requires (without it, page boundaries can repeat or skip rows —
    // "no deceptive pages"); the ranked variants keep their ranking first
    // with id as the tiebreaker for the same reason.

    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false
              AND status = 'ACTIVE'
              AND provider_id IN (:providerIds)
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
              AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
            ORDER BY id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false
                      AND status = 'ACTIVE'
                      AND provider_id IN (:providerIds)
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                      AND (:guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchByCriteriaRestricted(@Param("category") String category,
                                                     @Param("minPrice") Long minPrice,
                                                     @Param("maxPrice") Long maxPrice,
                                                     @Param("guests") Integer guests,
                                                     @Param("providerIds") java.util.Collection<UUID> providerIds,
                                                     Pageable pageable);

    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND provider_id IN (:providerIds)
              AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                  @@ websearch_to_tsquery('simple', :query)
            ORDER BY ts_rank(
                to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,'')),
                websearch_to_tsquery('simple', :query)
            ) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND provider_id IN (:providerIds)
                      AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                          @@ websearch_to_tsquery('simple', :query)
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchFullTextRestricted(@Param("query") String query,
                                                   @Param("providerIds") java.util.Collection<UUID> providerIds,
                                                   Pageable pageable);

    /**
     * Typo-tolerant fallback of the restricted FTS — same contract as
     * {@link #searchSimilar}, plus the provider whitelist.
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND provider_id IN (:providerIds)
              AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
            ORDER BY word_similarity(:query, coalesce(title,'') || ' ' || coalesce(description,'')) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND provider_id IN (:providerIds)
                      AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchSimilarRestricted(@Param("query") String query,
                                                  @Param("providerIds") java.util.Collection<UUID> providerIds,
                                                  Pageable pageable);

    // L32 (realestate systems plan §5) — listing-id-restricted variants (the
    // property-facet flow: the realestate module's matching-id set composes
    // onto the catalog query in the same restricted-branch shape). The
    // caller guarantees a NON-EMPTY collection (the empty set short-circuits
    // to an honest empty page before any query). Text queries keep their
    // relevance ranking with id as the tiebreaker.

    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND id IN (:listingIds)
              AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                  @@ websearch_to_tsquery('simple', :query)
            ORDER BY ts_rank(
                to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,'')),
                websearch_to_tsquery('simple', :query)
            ) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND id IN (:listingIds)
                      AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                          @@ websearch_to_tsquery('simple', :query)
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchFullTextRestrictedToListings(@Param("query") String query,
                                                             @Param("listingIds") Collection<UUID> listingIds,
                                                             Pageable pageable);

    /**
     * Typo-tolerant fallback of the listing-id-restricted FTS — same
     * contract as {@link #searchSimilar}, plus the listing whitelist.
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND id IN (:listingIds)
              AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
            ORDER BY word_similarity(:query, coalesce(title,'') || ' ' || coalesce(description,'')) DESC, id
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND id IN (:listingIds)
                      AND :query <% (coalesce(title,'') || ' ' || coalesce(description,''))
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchSimilarRestrictedToListings(@Param("query") String query,
                                                            @Param("listingIds") Collection<UUID> listingIds,
                                                            Pageable pageable);
}
