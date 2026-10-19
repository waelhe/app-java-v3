package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ProductTypeAttributeAssignmentRepository extends JpaRepository<ProductTypeAttributeAssignment, UUID> {
    List<ProductTypeAttributeAssignment> findAllByProductTypeIdOrderByScopeAscPositionAsc(UUID productTypeId);
    boolean existsByProductTypeIdAndAttributeDefinitionId(UUID productTypeId, UUID attributeDefinitionId);
    boolean existsByProductTypeIdAndScope(UUID productTypeId, ProductTypeAttributeScope scope);
    boolean existsByProductTypeIdAndScopeAndPosition(UUID productTypeId, ProductTypeAttributeScope scope, int position);
}
