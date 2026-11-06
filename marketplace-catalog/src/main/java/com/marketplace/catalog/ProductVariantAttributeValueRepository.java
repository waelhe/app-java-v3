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
     * ANY variant value answers with this option (the derived
     * exists-query over the entity's own {@code @SoftDelete} filter: a
     * soft-deleted variant's rows do not hold the option live).
     */
    boolean existsByOptionId(UUID optionId);
}
