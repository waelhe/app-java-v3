package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProductTypeAttributeAssignmentRepository extends JpaRepository<ProductTypeAttributeAssignment, UUID> {
    List<ProductTypeAttributeAssignment> findAllByProductTypeIdOrderByScopeAscPositionAsc(UUID productTypeId);
    boolean existsByProductTypeIdAndAttributeDefinitionId(UUID productTypeId, UUID attributeDefinitionId);
    boolean existsByProductTypeIdAndScope(UUID productTypeId, ProductTypeAttributeScope scope);
    boolean existsByProductTypeIdAndScopeAndPosition(UUID productTypeId, ProductTypeAttributeScope scope, int position);

    /**
     * The attribute's own assignment rows — the attribute delete's
     * cascade read (the wave-19 #808 review: the children go with the
     * deleted attribute, never left referencing a soft-deleted parent).
     */
    List<ProductTypeAttributeAssignment> findAllByAttributeDefinitionId(UUID attributeDefinitionId);

    /**
     * The product type's live-assignment probe — the delete refusal's
     * own read (the wave-19 #808 review: a product type with live
     * references answers the classified INVALID, never a silent delete
     * that orphans the schema).
     */
    boolean existsByProductTypeId(UUID productTypeId);
}
