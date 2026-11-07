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
    /** The required-PRODUCT guard's published-shelf probe (the #808 round-7). */
    private final ProductAttributeValueRepository productAttributeValues;
    /** The required-VARIANT guard's value-absence probe (the #808 round-9). */
    private final ProductVariantAttributeValueRepository variantAttributeValues;

    public ProductTypeAttributeService(ProductTypeRepository productTypes,
                                       CatalogAttributeDefinitionRepository definitions,
                                       CatalogAttributeOptionRepository options,
                                       ProductTypeAttributeAssignmentRepository assignments,
                                       ProductVariantRepository variantRepository,
                                       ProductAttributeValueRepository productAttributeValues,
                                       ProductVariantAttributeValueRepository variantAttributeValues) {
        this.productTypes = productTypes;
        this.definitions = definitions;
        this.options = options;
        this.assignments = assignments;
        this.variantRepository = variantRepository;
        this.productAttributeValues = productAttributeValues;
        this.variantAttributeValues = variantAttributeValues;
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
            // The 2026-11-06 #808 round-3 review, widened by the round-5
            // review (Major · Data Integrity & Integration): the
            // published-shelf guard (any live variant of a published
            // product — typed or free-form) refuses the assign; the
            // variants migrate first.
            if (variantRepository.existsPublishedShelfVariantForType(productTypeId)) {
                throw new ConflictException("A variant-selection attribute cannot be assigned while a published"
                        + " product of this type carries live variants — migrate the variants first");
            }
        }
        if (scope == ProductTypeAttributeScope.PRODUCT && required) {
            // The 2026-11-07 #808 round-7 review: the required-PRODUCT
            // assignment's own published-shelf guard — a product on the
            // shelf without a value for this attribute would carry the
            // new requirement as a conformance hole the shelf never
            // re-passes the publication gate to surface (a published
            // product's only self-heal is replaceOwnedAttributeValues'
            // enforced re-validation, which the owner may never call).
            // The behavior decision the finding demands: the SAME
            // fail-closed law the selection guard carries — the products
            // migrate first (the probe walks the definition's OWN live
            // assignments on this type, never the variant probe).
            if (productAttributeValues.existsPublishedShelfProductMissingValueFor(
                    productTypeId, attributeDefinitionId)) {
                // The 2026-11-07 #808 round-10 review: the refusal names
                // the EXECUTABLE migration — the optional assign, the
                // owners' value stores (replaceOwnedAttributeValues),
                // then the guarded promotion (setRequired) — never a
                // dead-end "migrate first" the duplicate-assign refusal
                // would strand.
                throw new ConflictException("A required product attribute cannot be assigned while a published"
                        + " product of this type lacks its value — assign it as optional, let the owners"
                        + " store the values, then promote it to required");
            }
        }
        if (scope == ProductTypeAttributeScope.VARIANT && required) {
            // The 2026-11-07 #808 round-9 review: the required-VARIANT
            // assignment's own mirror of the same law — a LIVE variant of
            // a published (or suspended — reactivate() returns it to
            // ACTIVE without re-running the publication gate) product
            // WITHOUT a value for this attribute would carry the new
            // requirement as the same conformance hole. The probe is the
            // VALUE-ABSENCE shape the finding demands (never the bare
            // published-variant probe — the NOT-EXISTS joins the
            // definition's OWN live assignments on this type); the
            // variants migrate first.
            if (variantAttributeValues.existsPublishedShelfVariantMissingValueFor(
                    productTypeId, attributeDefinitionId)) {
                // The 2026-11-07 #808 round-10 review: the same executable
                // migration naming — a live variant's values are written at
                // registration only (the pinned update contract's own
                // law), so the migration re-registers the variants with
                // the values before the guarded promotion (setRequired).
                throw new ConflictException("A required variant attribute cannot be assigned while a published"
                        + " product's live variant lacks its value — assign it as optional, migrate the"
                        + " variants' values, then promote it to required");
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
        // The 2026-11-07 #808 round-12 review (Major · Data Integrity &
        // Integration): the REST remove rides the SAME law the GraphQL
        // unassign path owns — the type write lock plus the live-values
        // wall. The bare find-and-delete let a used VARIANT_SELECTION
        // assignment go soft-deleted with the variant values still
        // bound to it (the stale combination keys then admitted a
        // duplicate registration under the shrunk schema — the
        // updateAssignments scope-move guard's own law, applied to the
        // remove shape), and a used PRODUCT assignment strand the
        // product (prepareProductAttributeValues' re-validation refuses
        // the dead assignment's values forever — the owner's only
        // escape hatch is releasing the values first, which is exactly
        // what the refusal message names).
        ProductTypeAttributeAssignment assignment = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductTypeAttributeAssignment", assignmentId));
        requireProductType(assignment.getProductTypeId());
        if (variantAttributeValues.existsLiveVariantValueForAssignmentsOrOptions(
                List.of(assignment.getId()), List.of())) {
            throw new ConflictException("An assignment referenced by a live variant cannot be removed"
                    + " — migrate the variants first");
        }
        if (productAttributeValues.existsLiveProductValueForAssignmentsOrOptions(
                List.of(assignment.getId()), List.of())) {
            throw new ConflictException("An assignment referenced by a live product cannot be removed"
                    + " — the owners release its values first");
        }
        assignments.delete(assignment);
    }

    /**
     * The 2026-11-07 #808 round-10 review (Major · Functional
     * Correctness): the required-attribute migration path's EXECUTABLE
     * half — the behavior the review demanded be chosen and documented.
     * The assign path's own guards (the round-7 PRODUCT and round-9
     * VARIANT probes) refuse a NEW required assignment while the
     * published shelf lacks its values, and the duplicate-assign refusal
     * strands the "fill the values, then re-assign" migration — so the
     * assignment lands OPTIONAL first and THIS path promotes it to
     * required under the SAME fail-closed law: the coverage probe of the
     * EXISTING live assignment (whose values the owners may now have
     * stored) refuses the promotion until every published-shelf row
     * carries the value. The demotion (required → optional) is a
     * relaxation that carries no conformance hole — the values stay, the
     * publication gate simply stops demanding them — so it passes without
     * a probe; the VARIANT_SELECTION scope keeps the assign path's own
     * every-variant law (its required flag is not demotable). The verdict
     * rides the SAME type write lock the assign path carries
     * (requireProductType) so the probe's shelf state serializes against
     * the publication and registration writers.
     */
    @Observed(name = "catalog.product-type-attribute.set-required")
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public ProductTypeAttributeAssignment setRequired(UUID assignmentId, boolean required) {
        // The 2026-11-07 #808 round-11 review (Minor · Data Integrity &
        // Integration): the assignment's owning type resolves FIRST (a
        // projection — no entity enters the persistence context), the
        // type write lock is taken, and ONLY THEN the assignment loads —
        // the assign path's own discipline. Loading the entity before
        // the lock let a concurrent updateAssignments scope move
        // (VARIANT → VARIANT_SELECTION, committed while this path waited
        // on the lock) leave the stale pre-move scope in the persistence
        // context: the every-variant law's check read the OLD scope (a
        // demotion adjudicated as VARIANT), and the save either answered
        // the raw OptimisticLockException or rewrote the old scope over
        // the committed move.
        UUID productTypeId = assignments.findProductTypeIdById(assignmentId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductTypeAttributeAssignment", assignmentId));
        requireProductType(productTypeId);
        ProductTypeAttributeAssignment assignment = assignments.findById(assignmentId)
                .filter(row -> row.getProductTypeId().equals(productTypeId))
                .orElseThrow(() -> new ResourceNotFoundException("ProductTypeAttributeAssignment", assignmentId));
        if (assignment.getScope() == ProductTypeAttributeScope.VARIANT_SELECTION && !required) {
            throw new ConflictException("VARIANT_SELECTION attributes must be required for every variant");
        }
        if (required && !assignment.isRequired()) {
            if (assignment.getScope() == ProductTypeAttributeScope.PRODUCT
                    && productAttributeValues.existsPublishedShelfProductMissingValueFor(
                            assignment.getProductTypeId(), assignment.getAttributeDefinitionId())) {
                throw new ConflictException("A required product attribute cannot land while a published product"
                        + " of this type still lacks its value — the owners store the values first,"
                        + " then the promotion passes");
            }
            if (assignment.getScope() == ProductTypeAttributeScope.VARIANT
                    && variantAttributeValues.existsPublishedShelfVariantMissingValueFor(
                            assignment.getProductTypeId(), assignment.getAttributeDefinitionId())) {
                throw new ConflictException("A required variant attribute cannot land while a published product's"
                        + " live variant still lacks its value — the variants re-register with the values"
                        + " first, then the promotion passes");
            }
        }
        assignment.reviseRequired(required);
        return assignments.save(assignment);
    }

    private void requireProductType(UUID id) {
        // The 2026-11-05 #808 round review (Minor · Data Integrity &
        // Integration): the locked read (the deleteProductType B21
        // comment's own intended pairing) — an unlocked findById could
        // read the type BEFORE the deleter's FOR-UPDATE lock, letting the
        // delete's live-references count see zero and soft-delete the
        // type, and the unlocked writer then committing an assignment
        // that references a soft-deleted type (the FK accepts the physical
        // row). The lock serializes the create against the delete's
        // committed verdict.
        //
        // The 2026-11-07 #808 round-4 review (Major · Data Integrity &
        // Integration): the lock is the WRITE lock (FOR-UPDATE) — the
        // assignAttributes twin's own discipline. The published-shelf
        // guard this path carries (existsPublishedShelfTypedVariantForType)
        // races the PUBLISH path's unlocked assignment read: the
        // publication validated the draft's variants against the OLD
        // schema while this guard saw the product still DRAFT, and both
        // committed — an ACTIVE product short of the new selection. The
        // write lock pairs with publishOwnedProduct's FOR-SHARE read of
        // the SAME row (taken BEFORE its assignment reads): either this
        // assign commits first and the publication re-reads the NEW
        // schema (its coverage verdict refuses), or the publication
        // committed first and this guard's probe sees the ACTIVE product
        // (the classified refusal). The write lock also serializes the
        // in-flight variant registrations (register's FOR-SHARE read of
        // the type waits for this writer's verdict).
        productTypes.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("ProductType", id));
    }
}
