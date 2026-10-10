package com.marketplace.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.UUID;

/**
 * The neighborhood feed's own repository (L42). The
 * {@link JpaSpecificationExecutor} arm is the official Spring Data JPA
 * Specifications entry point — "an extensible set of predicates …
 * removing the need to declare a query (method) for every needed
 * combination" — which the feed composes through
 * {@link NeighborhoodPostSpecifications} (D-N5: hasLocation/hasCategory/
 * VISIBLE-only, ordered on the complete sort key {@code created_at DESC,
 * id DESC}).
 *
 * <p>Hibernate's {@code @SoftDelete} filter hides author-deleted posts
 * from every derived query, so the feed never sees them without any
 * predicate of its own. The RevisionRepository arm carries the Envers
 * trail (V24 convention): every post and author delete is a revision the
 * moderation (L45) and export surfaces read.
 */
public interface NeighborhoodPostRepository
        extends JpaRepository<NeighborhoodPost, UUID>,
        JpaSpecificationExecutor<NeighborhoodPost>,
        RevisionRepository<NeighborhoodPost, UUID, Integer> {

    /**
     * Arabic PostgreSQL full-text search over title/body. Visibility,
     * soft-delete, location, and optional category predicates are repeated
     * in the count query so page metadata describes the same eligible set.
     * Relevance is followed by (created_at, id) to keep pagination stable.
     */
    @Query(value = """
            SELECT p.*
            FROM neighborhood_posts p
            WHERE p.location_id = :locationId
              AND p.is_deleted = FALSE
              AND p.status = 'VISIBLE'
              AND (:category IS NULL OR p.category = :category)
              AND to_tsvector('arabic', coalesce(p.title, '') || ' ' || coalesce(p.body, ''))
                  @@ websearch_to_tsquery('arabic', :query)
            ORDER BY ts_rank_cd(
                         to_tsvector('arabic', coalesce(p.title, '') || ' ' || coalesce(p.body, '')),
                         websearch_to_tsquery('arabic', :query)
                     ) DESC,
                     p.created_at DESC, p.id DESC
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM neighborhood_posts p
                    WHERE p.location_id = :locationId
                      AND p.is_deleted = FALSE
                      AND p.status = 'VISIBLE'
                      AND (:category IS NULL OR p.category = :category)
                      AND to_tsvector('arabic', coalesce(p.title, '') || ' ' || coalesce(p.body, ''))
                          @@ websearch_to_tsquery('arabic', :query)
                    """,
            nativeQuery = true)
    Page<NeighborhoodPost> searchVisibleFullText(
            @Param("locationId") UUID locationId,
            @Param("category") String category,
            @Param("query") String query,
            Pageable pageable);

    /**
     * Typo fallback using the pg_trgm word-similarity operator and the
     * same location/visibility/category predicates as full-text search.
     * The caller runs this only when full-text has zero total matches.
     * The default pg_trgm threshold is intentionally not overridden;
     * threshold tuning requires measurement.
     */
    @Query(value = """
            SELECT p.*
            FROM neighborhood_posts p
            WHERE p.location_id = :locationId
              AND p.is_deleted = FALSE
              AND p.status = 'VISIBLE'
              AND (:category IS NULL OR p.category = :category)
              AND :query <% (coalesce(p.title, '') || ' ' || coalesce(p.body, ''))
            ORDER BY word_similarity(:query, coalesce(p.title, '') || ' ' || coalesce(p.body, '')) DESC,
                     p.created_at DESC, p.id DESC
            """,
            countQuery = """
                    SELECT COUNT(*)
                    FROM neighborhood_posts p
                    WHERE p.location_id = :locationId
                      AND p.is_deleted = FALSE
                      AND p.status = 'VISIBLE'
                      AND (:category IS NULL OR p.category = :category)
                      AND :query <% (coalesce(p.title, '') || ' ' || coalesce(p.body, ''))
                    """,
            nativeQuery = true)
    Page<NeighborhoodPost> searchVisibleSimilar(
            @Param("locationId") UUID locationId,
            @Param("category") String category,
            @Param("query") String query,
            Pageable pageable);
}
