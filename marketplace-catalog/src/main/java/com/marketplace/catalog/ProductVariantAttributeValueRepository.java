package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
