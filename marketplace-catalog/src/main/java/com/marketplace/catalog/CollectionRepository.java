package com.marketplace.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2): the collection registry's
 * persistence port. {@code @SoftDelete} (BaseEntity) already filters
 * {@code is_deleted = false} from every derived query — the partial
 * unique index and these lookups stay aligned (no TOCTOU window, the V47
 * slug precedent).
 */
public interface CollectionRepository extends JpaRepository<Collection, UUID> {

    /** The write gate's precheck — a LIVE registry row for the slug or empty. */
    Optional<Collection> findBySlug(String slug);

    /**
     * The 2026-11-07 #808 round-18 review (Minor · Data Integrity &
     * Integration): the slug-uniqueness allocation's serialization seam
     * — the SAME advisory transaction lock discipline the membership
     * position allocation established (B6/CAT-01): createCollection and
     * updateCollection acquire the slug's own lock BEFORE the findBySlug
     * read, so two concurrent writers over the same slug serialize at
     * the check point and the {@code uq_collections_slug_live} partial
     * index stays a backstop, never the user-visible answer (an
     * unclassified 500 at commit). Lock key:
     * {@code hashtextextended('slug:' || slug, 0)} — namespaced away
     * from the collection-id keys, transaction-scoped (released at
     * COMMIT/ROLLBACK).
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT pg_advisory_xact_lock(hashtextextended('slug:' || :slug, 0))",
            nativeQuery = true)
    void lockSlugAllocation(@org.springframework.data.repository.query.Param("slug") String slug);

    /**
     * B8's own twin for the REGISTRY scroll (the 2026-11-09 #808
     * round-26 review, Minor · Performance &amp; Scalability — the
     * member-products scroll's documented conversion law): the
     * collections page was cut in MEMORY over the WHOLE registry
     * (findAll, the visibility filter, the sort, the slice), so every
     * page paid the full registry's heap and statement load. The pages
     * below ride Spring Data JPA's documented Pageable-on-@Query path
     * (setFirstResult/setMaxResults — the page is cut in SQL, never in
     * memory) over the registry's own rendered-name ordering key
     * ({@code COALESCE(name_en, name_ar, '')} — the exact rendering the
     * in-memory comparator used) with the id tiebreaker, the publication
     * law ({@code staff = false} carries the published rows alone)
     * filtered by the SAME statement, and the forward-only keyset
     * cursor riding the same pair. TEXT ordering answers the database's
     * own collation — the roster's SQL keyset precedent
     * (findByOrderByTitleAscIdAsc) already owns that law for the
     * platform's Arabic-first names.
     */
    @Query("""
            SELECT c FROM Collection c
            WHERE (:staff = TRUE OR c.published = TRUE)
            ORDER BY COALESCE(c.nameEn, c.nameAr, '') ASC, c.id ASC
            """)
    List<Collection> findFirstVisibleCollectionsByNameAsc(@Param("staff") boolean staff,
                                                          Pageable pageable);

    /** The cursor-continuation page — the SAME law above the (name, id) keyset cursor, ascending. */
    @Query("""
            SELECT c FROM Collection c
            WHERE (:staff = TRUE OR c.published = TRUE)
              AND (COALESCE(c.nameEn, c.nameAr, '') > :afterName
                   OR (COALESCE(c.nameEn, c.nameAr, '') = :afterName AND c.id > :afterId))
            ORDER BY COALESCE(c.nameEn, c.nameAr, '') ASC, c.id ASC
            """)
    List<Collection> findNextVisibleCollectionsByNameAsc(@Param("staff") boolean staff,
                                                         @Param("afterName") String afterName,
                                                         @Param("afterId") UUID afterId,
                                                         Pageable pageable);

    /** The first page's own DESC twin. */
    @Query("""
            SELECT c FROM Collection c
            WHERE (:staff = TRUE OR c.published = TRUE)
            ORDER BY COALESCE(c.nameEn, c.nameAr, '') DESC, c.id DESC
            """)
    List<Collection> findFirstVisibleCollectionsByNameDesc(@Param("staff") boolean staff,
                                                            Pageable pageable);

    /** The cursor-continuation page's own DESC twin. */
    @Query("""
            SELECT c FROM Collection c
            WHERE (:staff = TRUE OR c.published = TRUE)
              AND (COALESCE(c.nameEn, c.nameAr, '') < :afterName
                   OR (COALESCE(c.nameEn, c.nameAr, '') = :afterName AND c.id < :afterId))
            ORDER BY COALESCE(c.nameEn, c.nameAr, '') DESC, c.id DESC
            """)
    List<Collection> findNextVisibleCollectionsByNameDesc(@Param("staff") boolean staff,
                                                          @Param("afterName") String afterName,
                                                          @Param("afterId") UUID afterId,
                                                          Pageable pageable);

    /** The scroll's honest totalCount — the SAME visibility, ONE count statement. */
    @Query("SELECT count(c) FROM Collection c WHERE (:staff = TRUE OR c.published = TRUE)")
    long countVisibleCollections(@Param("staff") boolean staff);
}
