package com.marketplace.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductTypeAttributeAssignmentRepository extends JpaRepository<ProductTypeAttributeAssignment, UUID> {
    List<ProductTypeAttributeAssignment> findAllByProductTypeIdOrderByScopeAscPositionAsc(UUID productTypeId);
    boolean existsByProductTypeIdAndAttributeDefinitionId(UUID productTypeId, UUID attributeDefinitionId);
    boolean existsByProductTypeIdAndScope(UUID productTypeId, ProductTypeAttributeScope scope);
    boolean existsByProductTypeIdAndScopeAndPosition(UUID productTypeId, ProductTypeAttributeScope scope, int position);

    /**
     * The assignment's owning-type projection — the setRequired path's own
     * PRE-LOCK read (the 2026-11-07 #808 round-11 review: loading the
     * ENTITY before the type lock left a stale scope in the persistence
     * context — a concurrent updateAssignments scope move committed under
     * the lock, and the every-variant law's check then adjudicated the
     * OLD scope; the projection loads no entity, so the post-lock
     * findById reads the committed row).
     */
    @Query("select a.productTypeId from ProductTypeAttributeAssignment a where a.id = :id")
    Optional<UUID> findProductTypeIdById(@Param("id") UUID id);

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
