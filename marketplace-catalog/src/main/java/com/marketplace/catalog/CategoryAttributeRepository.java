package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the category-attribute
 * registry repository — the catalog module's own house shape
 * (JpaRepository + JpaSpecificationExecutor + Envers
 * RevisionRepository).
 */
public interface CategoryAttributeRepository extends JpaRepository<CategoryAttribute, UUID>,
        JpaSpecificationExecutor<CategoryAttribute>,
        RevisionRepository<CategoryAttribute, UUID, Integer> {

    /**
     * The registry's own read: one category's attributes in position
     * order — the deterministic total order (D-N5; the position key V89
     * keeps unique per category over the live rows).
     */
    List<CategoryAttribute> findByCategoryIdOrderByPositionAsc(UUID categoryId);

    /**
     * The administrative surface's identity lookup — the unique key's
     * read form ({@code uq_category_attributes_category_code}).
     */
    Optional<CategoryAttribute> findByCategoryIdAndCode(UUID categoryId, String code);

    /** The allocation seam for the write surface: the category's current max position (the W1 max-allocation lesson). */
    @org.springframework.data.jpa.repository.Query(
            "select max(a.position) from CategoryAttribute a where a.categoryId = :categoryId")
    Optional<Integer> findMaxPositionByCategoryId(UUID categoryId);

    /**
     * The registry-wide read the frontend's dynamic forms resolve per
     * category CODE — the join the public category read composes (the
     * attributes ride the category's own page/DTO, keyed by the stable
     * API-facing code V70 keeps unique over the live rows).
     */
    @org.springframework.data.jpa.repository.Query(
            "select a from CategoryAttribute a join Category c on a.categoryId = c.id "
                    + "where c.code = :categoryCode order by a.position asc")
    List<CategoryAttribute> findByCategoryCodeOrderByPositionAsc(String categoryCode);

    /**
     * The position-collision seam for the amendment surface (greptile W2
     * round, adopted from the root): does any OTHER live attribute of this
     * category already hold the requested position? The unique identity key
     * is (category, code) — position uniqueness over the live rows is the
     * service's own law, and this check is its read form.
     */
    boolean existsByCategoryIdAndPositionAndIdNot(UUID categoryId, int position, UUID id);

    /**
     * The registry's advisory transaction lock — the W1 r9 measured shape
     * ({@code pg_advisory_xct_lock(hashtextextended(:categoryId, 12))}),
     * seed 12 naming this family away from the media locks' 0, the reviewer
     * decisions' 7, and the business-page writes' 11. Held to commit, it
     * serializes the registry's position writes for ONE category: two
     * concurrent registrations never read the same maximum, and the
     * amendment's collision check can never race a same-position amendment
     * into a duplicate (the D-N5 total order's write-side guard).
     */
    @org.springframework.data.jpa.repository.Query(
            value = "SELECT pg_advisory_xct_lock(hashtextextended(:categoryId, 12))",
            nativeQuery = true)
    void lockRegistryWrites(
            @org.springframework.data.repository.query.Param("categoryId") String categoryId);
}
