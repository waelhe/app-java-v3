package com.marketplace.catalog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProviderListingRepository extends JpaRepository<ProviderListing, UUID> {

    Page<ProviderListing> findByProviderId(UUID providerId, Pageable pageable);

    Page<ProviderListing> findByCategoryAndStatus(String category, ListingStatus status, Pageable pageable);

    Page<ProviderListing> findByStatus(ListingStatus status, Pageable pageable);

    /**
     * Full-text search using PostgreSQL tsvector with GIN index.
     * Searches title and description columns.
     * Matches the GIN index defined in V9__search_index.sql.
     */
    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false AND status = 'ACTIVE'
              AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                  @@ to_tsquery('simple', :query)
            ORDER BY ts_rank(
                to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,'')),
                to_tsquery('simple', :query)
            ) DESC
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false AND status = 'ACTIVE'
                      AND to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))
                          @@ to_tsquery('simple', :query)
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchFullText(@Param("query") String query, Pageable pageable);

    @Query(value = """
            SELECT * FROM provider_listings
            WHERE is_deleted = false
              AND status = 'ACTIVE'
              AND (:category IS NULL OR category = :category)
              AND (:minPrice IS NULL OR price_cents >= :minPrice)
              AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
            """,
            countQuery = """
                    SELECT COUNT(*) FROM provider_listings
                    WHERE is_deleted = false
                      AND status = 'ACTIVE'
                      AND (:category IS NULL OR category = :category)
                      AND (:minPrice IS NULL OR price_cents >= :minPrice)
                      AND (:maxPrice IS NULL OR price_cents <= :maxPrice)
                    """,
            nativeQuery = true)
    Page<ProviderListing> searchByCriteria(@Param("category") String category,
                                           @Param("minPrice") Long minPrice,
                                           @Param("maxPrice") Long maxPrice,
                                           Pageable pageable);
}
