package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductAttributeValueRepository extends JpaRepository<ProductAttributeValue, UUID> {
    List<ProductAttributeValue> findAllByProductId(UUID productId);
    List<ProductAttributeValue> findAllByProductIdOrderByAssignmentIdAsc(UUID productId);

    /**
     * The 2026-11-06 #808 round-3 review (Major · Data Integrity &amp;
     * Integration): the option-delete guard's LIVE-PRODUCT value probe —
     * whether a product still on the shelf carries a CHOICE value bound to
     * this option. The JOIN carries the product's own {@code @SoftDelete}
     * filter (the ProductVariantAttributeValueRepository.existsByOptionId
     * twin's shape): a soft-deleted product's value rows no longer hold the
     * option live. Without this probe the option's soft delete left the
     * published product's value pointing at a dead option — the publish
     * path's re-validation (prepareProductAttributeValues) then refuses the
     * product forever on a row the registry itself orphaned.
     */
    @Query("""
            select (count(pav) > 0) from ProductAttributeValue pav
              join Product p on p.id = pav.productId
             where pav.optionId = :optionId
            """)
    boolean existsLiveProductValueForOption(@Param("optionId") UUID optionId);

    /**
     * The 2026-11-06 #808 round-3 review (Major · Data Integrity &amp;
     * Integration): the attribute-delete and unassignment guards' product
     * probe — whether a live product carries a value bound to one of the
     * given assignments or options (the same live filter the option probe
     * carries; the variant-value probe's own twin for the PRODUCT-scope
     * population).
     */
    @Query("""
            select (count(pav) > 0) from ProductAttributeValue pav
              join Product p on p.id = pav.productId
             where pav.assignmentId in :assignmentIds
                or pav.optionId in :optionIds
            """)
    boolean existsLiveProductValueForAssignmentsOrOptions(
            @Param("assignmentIds") Collection<UUID> assignmentIds,
            @Param("optionIds") Collection<UUID> optionIds);

    /**
     * The 2026-11-07 #808 round-7 review: the required-PRODUCT-assignment
     * guard's probe — whether a product on the published shelf (ACTIVE, or
     * SUSPENDED — the state {@code reactivate()} returns to ACTIVE without
     * re-running the publication gate) of this type carries NO live value
     * bound to any live assignment of the given attribute definition. A
     * required PRODUCT-scope assignment lands on such a product as a
     * conformance hole the shelf never re-passes the publication gate to
     * surface (the products migrate first — the same fail-closed law the
     * variant-selection guard carries; the NOT-EXISTS subquery joins the
     * definition's OWN live assignments on this type so a value bound to
     * a dead assignment never satisfies the coverage).
     */
    @Query("""
            select (count(p) > 0) from Product p
             where p.productTypeId = :productTypeId
               and p.status in (com.marketplace.catalog.StoreProductStatus.ACTIVE,
                                com.marketplace.catalog.StoreProductStatus.SUSPENDED)
               and not exists (
                   select v from ProductAttributeValue v
                    where v.productId = p.id
                      and v.assignmentId in (
                          select a.id from ProductTypeAttributeAssignment a
                           where a.productTypeId = :productTypeId
                             and a.attributeDefinitionId = :attributeDefinitionId))
            """)
    boolean existsPublishedShelfProductMissingValueFor(
            @Param("productTypeId") UUID productTypeId,
            @Param("attributeDefinitionId") UUID attributeDefinitionId);
}
