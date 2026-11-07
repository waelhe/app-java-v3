package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

/** Assigns reusable attributes to a product type and enforces variant-selection invariants. */
@Service
public class ProductTypeAttributeService {
    private final ProductTypeRepository productTypes;
    private final CatalogAttributeDefinitionRepository definitions;
    private final CatalogAttributeOptionRepository options;
    private final ProductTypeAttributeAssignmentRepository assignments;
    private final ProductVariantRepository variantRepository;

    public ProductTypeAttributeService(ProductTypeRepository productTypes,
                                       CatalogAttributeDefinitionRepository definitions,
                                       CatalogAttributeOptionRepository options,
                                       ProductTypeAttributeAssignmentRepository assignments,
                                       ProductVariantRepository variantRepository) {
        this.productTypes = productTypes;
        this.definitions = definitions;
        this.options = options;
        this.assignments = assignments;
        this.variantRepository = variantRepository;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    public List<ProductTypeAttributeAssignment> list(UUID productTypeId) {
        // The 2026-11-06 #808 round review (Major · Stability &
        // Availability): the read-only transaction CANNOT take the
        // FOR-SHARE lock — PostgreSQL rejects SELECT ... FOR SHARE in a
        // read-only transaction, so the list would answer a raw 500.
        // The lock pairs the WRITE paths with the delete wall; a read
        // surface never needed it (its own honest 404 stays).
        productTypes.findById(productTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductType", productTypeId));
        // The 2026-11-06 #808 round-3 review (Minor · Functional
        // Correctness): the BUCKET-major order — PRODUCT's own bucket
        // first, then the VARIANT family (VARIANT, VARIANT_SELECTION),
        // with the (position, scope, id) order INSIDE each bucket. The
        // previous position-major sort interleaved the buckets
        // (PRODUCT@1, VARIANT@1, PRODUCT@2...), so a client's
        // relative-move request addressed neighbors the reorder path
        // (the repository's scope-major bucket order) never resolved —
        // the read surface and the write surface now share ONE bucket
        // order.
        return assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId).stream()
                .sorted(java.util.Comparator
                        .comparingInt((ProductTypeAttributeAssignment assignment) ->
                                assignment.getScope() == ProductTypeAttributeScope.PRODUCT ? 0 : 1)
                        .thenComparingInt(ProductTypeAttributeAssignment::getPosition)
                        .thenComparing(assignment -> assignment.getScope().ordinal())
                        .thenComparing(ProductTypeAttributeAssignment::getId))
                .toList();
    }

    @Observed(name = "catalog.product-type-attribute.assign")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductTypeAttributeAssignment assign(UUID productTypeId, UUID attributeDefinitionId,
                                                 ProductTypeAttributeScope scope, boolean required, int position) {
        requireProductType(productTypeId);
        // The 2026-11-06 #808 round review (Major · Data Integrity &
        // Integration): the definition read takes the row FOR SHARE —
        // the attribute-delete path's FOR-UPDATE read of the same row
        // serializes against THIS read (the assignment this method is
        // about to create is the reference the delete's children-guard
        // probes; an unlocked read let the delete pass with zero
        // assignments while this insert was in flight — the FK accepts
        // the physical row, the assignment stays live against a
        // soft-deleted attribute).
        CatalogAttributeDefinition definition = definitions.findByIdForShare(attributeDefinitionId)
                .orElseThrow(() -> new ResourceNotFoundException("CatalogAttributeDefinition", attributeDefinitionId));

        if (scope == ProductTypeAttributeScope.VARIANT_SELECTION) {
            if (!required) {
                throw new ConflictException("VARIANT_SELECTION attributes must be required for every variant");
            }
            if (definition.getValueType() != CatalogAttributeType.CHOICE) {
                throw new ConflictException("VARIANT_SELECTION requires a CHOICE attribute");
            }
            if (options.countByAttributeDefinitionId(attributeDefinitionId) < 2) {
                throw new ConflictException("VARIANT_SELECTION requires at least two allowed options");
            }
            // The 2026-11-06 #808 round-3 review (Major · Data Integrity &
            // Integration): the published-shelf guard — a product on the
            // shelf (ACTIVE, or SUSPENDED — reactivate() returns it to
            // ACTIVE without re-running the publication gate) carrying a
            // TYPED variant would keep its key derived under the dead
            // schema and its values short of the new selection, and the
            // shelf never re-passes the gate to surface it. The variants
            // migrate first (the attribute-delete wall's own law); DRAFT
            // products stay eligible — the publication gate owns their
            // coverage verdict at publish time (the
            // assignAttributes twin's own pairing).
            if (variantRepository.existsPublishedShelfTypedVariantForType(productTypeId)) {
                throw new ConflictException("A variant-selection attribute cannot be assigned while a published"
                        + " product of this type carries typed variants — migrate the variants first");
            }
        }

        if (assignments.existsByProductTypeIdAndAttributeDefinitionId(productTypeId, attributeDefinitionId)) {
            throw new ConflictException("Attribute is already assigned to this product type");
        }
        if (assignments.existsByProductTypeIdAndScopeAndPosition(productTypeId, scope, position)) {
            throw new ConflictException("Attribute position " + position + " is already used in scope " + scope);
        }
        return assignments.save(ProductTypeAttributeAssignment.create(
                productTypeId, attributeDefinitionId, scope, required, position));
    }

    @Observed(name = "catalog.product-type-attribute.remove")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void remove(UUID assignmentId) {
        ProductTypeAttributeAssignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductTypeAttributeAssignment", assignmentId));
        assignments.delete(assignment);
    }

    private void requireProductType(UUID id) {
        // The 2026-11-06 #808 round review (Minor · Data Integrity &
        // Integration): the FOR-SHARE read (PESSIMISTIC_READ, the
        // deleteProductType B21 comment's own intended pairing) — an
        // unlocked findById could read the type BEFORE the deleter's
        // FOR-UPDATE lock, letting the delete's live-references count see
        // zero and soft-delete the type, and the unlocked writer then
        // committing an assignment that references a soft-deleted type
        // (the FK accepts the physical row). The share lock serializes
        // the create against the delete's committed verdict.
        productTypes.findByIdForShare(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductType", id));
    }
}
