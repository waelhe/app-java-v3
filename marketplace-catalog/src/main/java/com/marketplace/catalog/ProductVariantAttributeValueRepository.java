package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface ProductVariantAttributeValueRepository extends JpaRepository<ProductVariantAttributeValue, UUID> {
    List<ProductVariantAttributeValue> findAllByProductVariantId(UUID productVariantId);
    List<ProductVariantAttributeValue> findAllByProductVariantIdOrderByAssignmentIdAsc(UUID productVariantId);

    /**
     * The 2026-11-06 #808 round review (Major · Data Integrity &amp;
     * Integration): the option-delete guard's live-reference probe — whether
     * ANY LIVE variant answers with this option. The JOIN carries the
     * variant's own {@code @SoftDelete} filter: a soft-deleted variant's
     * value rows no longer hold the option live ({@code deleteVariant}
     * soft-deletes the variant alone, never its values — the derived
     * single-table probe kept blocking the option's delete forever with
     * a "referenced by a live variant" message that named a dead row).
     */
    @org.springframework.data.jpa.repository.Query("""
            select (count(vav) > 0) from ProductVariantAttributeValue vav
              join ProductVariant v on v.id = vav.productVariantId
             where vav.optionId = :optionId
            """)
    boolean existsByOptionId(
            @org.springframework.data.repository.query.Param("optionId") UUID optionId);

    /**
     * The 2026-11-06 #808 round review (Major · Data Integrity &amp;
     * Integration): the attribute-deletion and unassignment guards' probe —
     * whether ANY LIVE variant carries a value bound to one of the given
     * assignments or options (the same two-entity live filter the option
     * probe carries: a deleted variant's rows never block).
     */
    @org.springframework.data.jpa.repository.Query("""
            select (count(vav) > 0) from ProductVariantAttributeValue vav
              join ProductVariant v on v.id = vav.productVariantId
             where vav.assignmentId in :assignmentIds
                or vav.optionId in :optionIds
            """)
    boolean existsLiveVariantValueForAssignmentsOrOptions(
            @org.springframework.data.repository.query.Param("assignmentIds") java.util.Collection<UUID> assignmentIds,
            @org.springframework.data.repository.query.Param("optionIds") java.util.Collection<UUID> optionIds);

    /**
     * The 2026-11-07 #808 round-9 review: the required-VARIANT-assignment
     * guard's probe — whether a LIVE variant of a published-shelf (ACTIVE,
     * or SUSPENDED — the state {@code reactivate()} returns to ACTIVE
     * without re-running the publication gate) product of the type carries
     * NO live value bound to any live assignment of the given attribute
     * definition. A required VARIANT-scope assignment lands on such a
     * variant as a conformance hole the shelf never re-passes the
     * publication gate to surface — the VALUE-ABSENCE shape the finding
     * demands (never the bare published-variant probe; the NOT-EXISTS
     * subquery joins the definition's OWN live assignments on this type so
     * a value bound to a dead assignment never satisfies the coverage).
     *
     * <p>The 2026-11-07 #808 round-14 review (Minor · Functional
     * Correctness): ARCHIVED variants are EXCLUDED — the state is terminal
     * ({@code updateVariant} refuses it, {@code reactivate()} never returns
     * it to the shelf), and the publication gate checks ACTIVE variants
     * only, so an archived row must never block the promotion with a
     * re-register demand for a row that cannot re-enter the shelf.
     * SUSPENDED stays IN the probe: {@code reactivate()} returns it to
     * ACTIVE without re-running the gate.
     */
    @Query("""
            select (count(v) > 0) from ProductVariant v
              join Product p on p.id = v.productId
             where p.productTypeId = :productTypeId
               and p.status in (com.marketplace.catalog.StoreProductStatus.ACTIVE,
                                com.marketplace.catalog.StoreProductStatus.SUSPENDED)
               and v.status <> com.marketplace.catalog.ProductStatus.ARCHIVED
               and not exists (
                   select vav from ProductVariantAttributeValue vav
                    where vav.productVariantId = v.id
                      and vav.assignmentId in (
                          select a.id from ProductTypeAttributeAssignment a
                           where a.productTypeId = :productTypeId
                             and a.attributeDefinitionId = :attributeDefinitionId))
            """)
    boolean existsPublishedShelfVariantMissingValueFor(
            @Param("productTypeId") UUID productTypeId,
            @Param("attributeDefinitionId") UUID attributeDefinitionId);
}
