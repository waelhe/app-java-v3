package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
