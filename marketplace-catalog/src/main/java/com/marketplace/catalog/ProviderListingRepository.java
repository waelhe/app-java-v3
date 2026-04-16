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
            nativeQuery = true)
    Page<ProviderListing> searchFullText(@Param("query") String query, Pageable pageable);
}
