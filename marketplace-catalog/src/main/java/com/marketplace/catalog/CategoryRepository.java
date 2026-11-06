package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * S6 (comprehensive repair plan §10/2.3): the registry's persistence port.
 * {@code @SoftDelete} (BaseEntity) already filters {@code is_deleted = false}
 * from every derived query — the partial unique index and these lookups stay
 * aligned (no TOCTOU window, the V47 slug precedent).
 */
public interface CategoryRepository extends JpaRepository<Category, java.util.UUID> {

    /** The write gate's precheck — an ACTIVE registry row for the code or empty. */
    Optional<Category> findByCode(String code);

    /**
     * The reserved-code check (the 2026-11-05 #808 round, Major ·
     * Functional Correctness): V70's unique index on {@code code} spans
     * the RAW table — soft-deleted rows included — while
     * {@code @SoftDelete} filters them from every derived query, so a
     * soft-deleted category's code passed {@code findByCode} and the
     * INSERT then died on the raw index violation instead of the
     * classified ALREADY_EXISTS. The native count reads the raw table
     * (no soft-delete filter): both live AND reserved codes answer the
     * honest mutation error BEFORE the save. The native-query mechanism
     * is the house's own documented way past the soft-delete filter
     * (the AdCampaignRepository precedent).
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT COUNT(*) FROM categories WHERE code = :code", nativeQuery = true)
    long countByCodeIncludingDeleted(@org.springframework.data.repository.query.Param("code") String code);

    /** The public read, in the registry's display order. */
    List<Category> findAllByOrderByPositionAsc();

    /**
     * The 2026-11-06 #808 round review (Minor · Data Integrity &amp;
     * Integration): the registry's display order with the fixed {@code id}
     * tie-break — the append position is a max+1 derivation with no
     * position-unique index, so two concurrent creates can legitimately
     * land the SAME position; the tie-break keeps every read's order
     * deterministic (never an unstable interleave of equal positions).
     */
    List<Category> findAllByOrderByPositionAscIdAsc();
}
