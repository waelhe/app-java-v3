package com.marketplace.catalog;

import com.marketplace.catalog.spi.AttributeReorderMove;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeAssignmentUpdateOperation;
import com.marketplace.catalog.spi.ProductTypeAttributeWriteSpi;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import com.marketplace.catalog.spi.ProductTypeView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productAttributeAssign},
 * {@code productAttributeAssignmentUpdate},
 * {@code productAttributeUnassign} and
 * {@code productTypeReorderAttributes} mutation roots, the agent-2
 * follow-on packet): the owning use case for the product-type
 * attribute schema's ADMIN write surface over the EXISTING
 * {@code product_type_attribute_assignments} registry — the same
 * registry, invariants and ownership the module's own
 * {@code ProductTypeAttributeService} established for the REST
 * surface, answered in the registered "Mutation payload errors" ADAPT
 * shape instead of REST exceptions. Authorization is enforced HERE
 * at the owning use-case boundary
 * ({@code @PreAuthorize("hasRole('ADMIN')")}), never only in a
 * resolver.
 *
 * <p><b>The scope model mapping is the platform's own:</b> the pinned
 * input's {@code type} (PRODUCT/VARIANT) plus the
 * {@code variantSelection} flag address the platform's three-scope
 * model. A VARIANT_SELECTION assignment re-checks the module's own
 * invariants before the write (the CHOICE value shape, the two-option
 * floor, the required-every-variant law —
 * {@code ProductTypeAttributeService.assign}'s own rules); a
 * PRODUCT-typed attribute cannot simultaneously be a variant
 * selection (the INVALID combination). The append position is the
 * OWNER's own max+1 derivation within the (product type, scope)
 * bucket — never a client-supplied fact; the position-uniqueness
 * column remains the concurrent-write backstop.</p>
 *
 * <p><b>The reorder resolves the pinned relative moves</b> against
 * the bucket's current order: each move's {@code sortOrder} shifts
 * the addressed assignment within the bucket's ordered list, the
 * amended list re-derives compacted 1..n positions, and only the
 * moved rows write. Unknown ids answer the honest NOT_FOUND; an
 * out-of-range shift answers INVALID (the relative move cannot leave
 * the bucket).</p>
 */
@Service
public class ProductTypeAttributeWriteService implements ProductTypeAttributeWriteSpi {

    private final ProductTypeRepository productTypeRepository;
    private final CatalogAttributeDefinitionRepository attributeDefinitionRepository;
    private final CatalogAttributeOptionRepository attributeOptionRepository;
    private final ProductTypeAttributeAssignmentRepository assignmentRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductVariantAttributeValueRepository variantAttributeValues;

    public ProductTypeAttributeWriteService(ProductTypeRepository productTypeRepository,
                                            CatalogAttributeDefinitionRepository attributeDefinitionRepository,
                                            CatalogAttributeOptionRepository attributeOptionRepository,
                                            ProductTypeAttributeAssignmentRepository assignmentRepository,
                                            ProductRepository productRepository,
                                            ProductVariantRepository variantRepository,
                                            ProductVariantAttributeValueRepository variantAttributeValues) {
        this.productTypeRepository = productTypeRepository;
        this.attributeDefinitionRepository = attributeDefinitionRepository;
        this.attributeOptionRepository = attributeOptionRepository;
        this.assignmentRepository = assignmentRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.variantAttributeValues = variantAttributeValues;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductTypeMutationResult assignAttributes(UUID productTypeId,
                                                      List<ProductTypeAttributeAssignOperation> operations) {
        // The 2026-11-05 #808 round (Minor · Functional Correctness): the
        // locked read — deleteProductType takes FOR UPDATE then checks
        // live references, so an unlocked findById here raced the delete
        // (the TOCTOU B21 closed on the productType-create side). The
        // same lock the delete holds serializes this existence check
        // against it.
        Optional<ProductType> type = productTypeId == null
                ? Optional.empty() : productTypeRepository.findByIdForUpdate(productTypeId);
        if (type.isEmpty()) {
            return ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                    "productTypeId", ProductTypeMutationErrorCode.NOT_FOUND,
                    "No product type found for the id")));
        }
        List<ProductTypeMutationError> errors = new ArrayList<>();
        List<ProductTypeAttributeAssignment> toSave = new ArrayList<>();
        // The wave-19 #808 review (Major · Functional Correctness): the
        // append position is derived per (product type, scope) bucket and
        // the duplicate probe reads the DATABASE only — neither sees the
        // rows this request has already staged. The in-memory pending
        // ledger closes both doors: every staged scope carries its own
        // running position, and every staged attribute id refuses a
        // second assignment inside the same request (the unique index
        // would otherwise answer with an unclassified constraint throw).
        Map<ProductTypeAttributeScope, Integer> pendingNextPosition = new HashMap<>();
        Set<UUID> pendingAttributeIds = new HashSet<>();
        for (ProductTypeAttributeAssignOperation operation : operations) {
            String typeMember = operation.attributeType();
            boolean variantSelection = Boolean.TRUE.equals(operation.variantSelection());
            if (!"PRODUCT".equals(typeMember) && !"VARIANT".equals(typeMember)) {
                errors.add(new ProductTypeMutationError("type", ProductTypeMutationErrorCode.INVALID,
                        "An attribute is assigned as a PRODUCT or VARIANT type"));
                continue;
            }
            if (variantSelection && "PRODUCT".equals(typeMember)) {
                errors.add(new ProductTypeMutationError("variantSelection", ProductTypeMutationErrorCode.INVALID,
                        "A PRODUCT-typed attribute cannot be a variant selection"));
                continue;
            }
            Optional<CatalogAttributeDefinition> definition = operation.attributeId() == null
                    // The 2026-11-06 #808 round review (Major · Data
                    // Integrity & Integration): the reference-CREATING
                    // read takes the definition row FOR SHARE — the
                    // attribute-delete wall's FOR-UPDATE read of the same
                    // row serializes against the assignment this method
                    // is about to save (the ProductTypeAttributeService
                    // .assign pairing verbatim).
                    ? Optional.empty() : attributeDefinitionRepository.findByIdForShare(operation.attributeId());
            if (definition.isEmpty()) {
                errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.NOT_FOUND,
                        "No attribute found for the id"));
                continue;
            }
            ProductTypeAttributeScope scope = "PRODUCT".equals(typeMember)
                    ? ProductTypeAttributeScope.PRODUCT
                    : (variantSelection
                        ? ProductTypeAttributeScope.VARIANT_SELECTION
                        : ProductTypeAttributeScope.VARIANT);
            if (scope == ProductTypeAttributeScope.VARIANT_SELECTION) {
                // The module's own invariants, classified as the
                // payload's own INVALID outcomes — never REST throws.
                if (definition.get().getValueType() != CatalogAttributeType.CHOICE) {
                    errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                            "A variant-selection attribute requires the CHOICE value shape"));
                    continue;
                }
                if (attributeOptionRepository.countByAttributeDefinitionId(operation.attributeId()) < 2) {
                    errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                            "A variant-selection attribute requires at least two allowed options"));
                    continue;
                }
                // The 2026-11-06 #808 round-3 review (Major · Data
                // Integrity & Integration), widened by the round-5 review
                // (Major · Data Integrity & Integration): the
                // published-shelf guard — the new selection's value
                // cannot exist on any variant yet (the attribute is NEW to
                // this type), so a product on the shelf (ACTIVE, or
                // SUSPENDED — reactivate() returns it to ACTIVE without
                // re-running the publication gate) carrying ANY live
                // variant (TYPED or free-form NULL-KEY — the round-5
                // widening: a selection-free type publishes with a
                // free-form active variant) would keep its variants short
                // of the new selection and the shelf never re-passes the
                // gate to surface it. The variants migrate first; DRAFT
                // products stay eligible — the publication gate owns their
                // coverage verdict at publish time (the
                // ProductTypeAttributeService.assign twin's own pairing).
                if (variantRepository.existsPublishedShelfVariantForType(productTypeId)) {
                    errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                            "A variant-selection attribute cannot be assigned while a published product of"
                                    + " this type carries live variants — migrate the variants first"));
                    continue;
                }
            }
            if (assignmentRepository.existsByProductTypeIdAndAttributeDefinitionId(
                    productTypeId, operation.attributeId())
                    || pendingAttributeIds.contains(operation.attributeId())) {
                errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.ALREADY_EXISTS,
                        "The attribute is already assigned to this product type"));
                continue;
            }
            int position = pendingNextPosition.computeIfAbsent(scope,
                    candidate -> nextPosition(productTypeId, candidate));
            pendingNextPosition.put(scope, position + 1);
            pendingAttributeIds.add(operation.attributeId());
            toSave.add(ProductTypeAttributeAssignment.create(productTypeId, operation.attributeId(),
                    scope, scope == ProductTypeAttributeScope.VARIANT_SELECTION,
                    position));
        }
        if (!errors.isEmpty()) {
            return ProductTypeMutationResult.failure(errors);
        }
        if (!toSave.isEmpty()) {
            assignmentRepository.saveAll(toSave);
        }
        return ProductTypeMutationResult.success(toView(type.get()));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductTypeMutationResult updateAssignments(UUID productTypeId,
                                                       List<ProductTypeAttributeAssignmentUpdateOperation> operations) {
        // The 2026-11-06 #808 round review (Minor · Stability &
        // Availability): the SAME PESSIMISTIC_WRITE type load the assign
        // path carries — every writer that derives positions from a
        // prior read (updateAssignments' pendingNextPosition,
        // reorderAttributes' park range) serializes on the type row, so
        // no two writers can derive the same position and answer the
        // unique index's raw error instead of the classified one.
        Optional<ProductType> type = productTypeId == null
                ? Optional.empty() : productTypeRepository.findByIdForUpdate(productTypeId);
        if (type.isEmpty()) {
            return ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                    "productTypeId", ProductTypeMutationErrorCode.NOT_FOUND,
                    "No product type found for the id")));
        }
        List<ProductTypeMutationError> errors = new ArrayList<>();
        List<ProductTypeAttributeAssignment> toSave = new ArrayList<>();
        // The 2026-11-05 #808 round (Major · Functional Correctness): the
        // deferred moves — nothing mutates a managed entity before the
        // failure verdict passes.
        List<DeferredMove> deferredMoves = new ArrayList<>();
        // The 2026-11-06 #808 round review (Minor · Functional
        // Correctness): ONE operation per attribute per call — a repeated
        // attributeId read the same deferred (not-yet-mutated) row twice,
        // staging two moves for one entity where the last target won
        // silently while the first's allocated position stayed a wasted
        // gap. The duplicate answers the classified INVALID refusal: the
        // whole operation is deterministic, never first-wins or
        // last-wins by accident.
        java.util.Set<UUID> seenAttributeIds = new java.util.HashSet<>();
        // The wave-19 #808 review (Major · Functional Correctness): the
        // moved row's position must be derived from the TARGET scope
        // BEFORE the move (after moveToScope the moved row itself sits in
        // the target bucket with its old position, so the max+1 read
        // would stride over it), and two moves staged into the same
        // target scope share the in-memory running position — the unique
        // index (product_type_id, scope, position) stays behind the
        // classified INVALID vocabulary, never a constraint throw.
        Map<ProductTypeAttributeScope, Integer> pendingNextPosition = new HashMap<>();
        for (ProductTypeAttributeAssignmentUpdateOperation operation : operations) {
            if (!seenAttributeIds.add(operation.attributeId())) {
                errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                        "One operation per attribute — the attribute id is repeated"));
                continue;
            }
            Optional<ProductTypeAttributeAssignment> addressed = findAssignment(
                    productTypeId, operation.attributeId());
            if (addressed.isEmpty()) {
                errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.NOT_FOUND,
                        "No assigned attribute found for the id"));
                continue;
            }
            ProductTypeAttributeAssignment assignment = addressed.get();
            ProductTypeAttributeScope target = operation.variantSelection()
                    ? ProductTypeAttributeScope.VARIANT_SELECTION
                    : ProductTypeAttributeScope.VARIANT;
            // The scope never moves a PRODUCT-typed assignment: the
            // pinned input's only movable fact is the
            // variant-selection membership of a VARIANT-family
            // attribute.
            if (assignment.getScope() == ProductTypeAttributeScope.PRODUCT) {
                errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                        "A PRODUCT-typed assignment's variant-selection membership is immutable"));
                continue;
            }
            if (target == ProductTypeAttributeScope.VARIANT_SELECTION
                    && assignment.getScope() != ProductTypeAttributeScope.VARIANT_SELECTION) {
                Optional<CatalogAttributeDefinition> definition =
                        // The same round: the CHOICE-gate read rides the
                        // FOR-SHARE row lock (the move stages a reference-
                        // carrying assignment; the delete wall serializes
                        // against it).
                        attributeDefinitionRepository.findByIdForShare(operation.attributeId());
                if (definition.isEmpty()
                        || definition.get().getValueType() != CatalogAttributeType.CHOICE) {
                    errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                            "A variant-selection attribute requires the CHOICE value shape"));
                    continue;
                }
                if (attributeOptionRepository.countByAttributeDefinitionId(operation.attributeId()) < 2) {
                    errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                            "A variant-selection attribute requires at least two allowed options"));
                    continue;
                }
            }
            if (assignment.getScope() != target) {
                int targetPosition = pendingNextPosition.computeIfAbsent(target,
                        candidate -> nextPosition(productTypeId, candidate));
                pendingNextPosition.put(target, targetPosition + 1);
                // The 2026-11-05 #808 round (Major · Functional
                // Correctness): the move is DEFERRED — the assignment is
                // a managed entity, so mutating it before the failure
                // verdict left the scope/position writes committed even
                // when the result answered failure (the transaction
                // commits the managed mutations regardless of the
                // returned verdict). The moves apply only after the
                // error gate.
                toSave.add(assignment);
                deferredMoves.add(new DeferredMove(assignment, target, targetPosition));
            }
        }
        if (!errors.isEmpty()) {
            return ProductTypeMutationResult.failure(errors);
        }
        // The 2026-11-06 #808 round review (Major · Data Integrity &
        // Integration): the scope-consistency verdict — the selection
        // schema's move re-keys the AFFECTED variants or refuses the
        // move, and NOTHING mutates before the verdict passes (the
        // deferred-move discipline's own law: a managed entity mutated
        // before a failure return still commits). The guard computes the
        // post-move selection set in memory, verifies every typed
        // variant of the type's products COVERS it, re-derives each
        // variant's combination key over the post-move schema, and
        // refuses on a coverage gap or a per-product key collision —
        // the alternative (leave the old keys) left two holes: a variant
        // whose key missed the moved attribute could never collide with
        // a fresh registration's new-shape key (duplicates registered),
        // and two existing variants that differed only in a
        // moved-OUT attribute kept distinct old keys over what the new
        // schema calls the same combination.
        List<PendingRecombine> pendingRecombines = new ArrayList<>();
        ProductTypeMutationError consistencyError =
                verifySelectionConsistency(productTypeId, deferredMoves, pendingRecombines);
        if (consistencyError != null) {
            return ProductTypeMutationResult.failure(List.of(consistencyError));
        }
        // The verdict passed — the deferred moves apply now, inside the
        // same transaction, exactly once.
        for (DeferredMove move : deferredMoves) {
            move.assignment().moveToScope(move.target());
            move.assignment().reposition(move.position());
        }
        if (!toSave.isEmpty()) {
            assignmentRepository.saveAll(toSave);
        }
        // The re-derivations ride the same committed verdict — the
        // variants' keys now match the schema their own assignment rows
        // just moved into (the registration path's own bytes).
        if (!pendingRecombines.isEmpty()) {
            variantRepository.saveAll(pendingRecombines.stream()
                    .map(pending -> {
                        pending.variant().recombine(pending.combinationKey());
                        return pending.variant();
                    })
                    .toList());
        }
        return ProductTypeMutationResult.success(toView(type.get()));
    }

    /**
     * The scope-consistency verdict (the 2026-11-06 #808 round review,
     * Major · Data Integrity &amp; Integration) — PURE verification, zero
     * mutation: walks the type's live products' typed variants (a null
     * combination key is the legacy free-form population — the
     * publication gate owns its refusal, never this move), checks each
     * variant COVERS the post-move selection set with an option-bearing
     * value per selection assignment, re-derives the variant's key over
     * that set, and detects the per-product collision the re-derivation
     * can create. The planned re-derivations land in {@code planned} for
     * the caller to apply ONLY after the whole verdict passes.
     *
     * @return the classified refusal, or null when the move preserves
     *         every guarantee
     */
    private ProductTypeMutationError verifySelectionConsistency(UUID productTypeId,
                                                                 List<DeferredMove> deferredMoves,
                                                                 List<PendingRecombine> planned) {
        if (deferredMoves.isEmpty()) {
            // No scope move — the selection set is untouched and every
            // existing key stays the schema's own truth.
            return null;
        }
        // The post-move selection set, derived IN MEMORY (the managed
        // assignment rows are still pre-move — the deferred discipline
        // holds until the verdict). An EMPTY post-move set (the last
        // selection attribute moving out) is a legal verdict the walk
        // below answers: every walked variant recombines to the null
        // free-form key (the 2026-11-06 #808 round-3 review — null keys
        // never collide, the index excludes them).
        java.util.Map<UUID, ProductTypeAttributeScope> targetById = new java.util.HashMap<>();
        for (DeferredMove move : deferredMoves) {
            targetById.put(move.assignment().getId(), move.target());
        }
        List<ProductTypeAttributeAssignment> postMoveSelection = assignmentRepository
                .findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId).stream()
                .filter(assignment -> targetById.getOrDefault(
                        assignment.getId(), assignment.getScope())
                        == ProductTypeAttributeScope.VARIANT_SELECTION)
                .toList();
        for (Product product : productRepository.findByProductTypeId(productTypeId)) {
            java.util.Map<String, ProductVariant> newKeyOwners = new java.util.HashMap<>();
            for (ProductVariant variant : variantRepository.findByProductIdOrderBySkuAsc(product.getId())) {
                java.util.Map<UUID, CatalogAttributeValueInput> valuesByAssignment = new java.util.HashMap<>();
                for (ProductVariantAttributeValue value : variantAttributeValues
                        .findAllByProductVariantIdOrderByAssignmentIdAsc(variant.getId())) {
                    valuesByAssignment.put(value.getAssignmentId(), value.toInput());
                }
                // The 2026-11-06 #808 round-3 review (Major · Data Integrity
                // & Integration): the null-key variant is NO LONGER
                // skipped — the move OUT of the last selection attribute
                // turns every typed variant free-form (canonical answers
                // null for the empty selection set), and a later move BACK
                // IN must re-derive those keys (the values never left the
                // rows), never strand them outside the schema. The
                // coverage law is the same one the typed population
                // answers: a variant the post-move selection set leaves
                // without an option-bearing value refuses the move (the
                // migrate-first verdict) — free-form rows that never
                // carried the value are exactly that population.
                for (ProductTypeAttributeAssignment selection : postMoveSelection) {
                    CatalogAttributeValueInput covered = valuesByAssignment.get(selection.getId());
                    if (covered == null || covered.optionId() == null) {
                        return new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                                "The variant-selection move leaves variant '" + variant.getSku()
                                        + "' without an option for a selection attribute — "
                                        + "migrate the variant before the move");
                    }
                }
                String rederived = VariantCombinationKeys.canonical(postMoveSelection, valuesByAssignment);
                if (rederived == null) {
                    // The post-move schema carries NO selection attribute:
                    // every walked variant becomes a free-form row. The
                    // null key never collides (the partial unique index
                    // excludes it by its own predicate) — the previous
                    // putIfAbsent refusal on the second null answered a
                    // collision the index itself cannot produce. The
                    // recombine still stages the null key: the row's key
                    // must match the schema that no longer derives one
                    // (the re-derivation symmetry the finding demands).
                    planned.add(new PendingRecombine(variant, null));
                    continue;
                }
                ProductVariant clash = newKeyOwners.putIfAbsent(rederived, variant);
                if (clash != null) {
                    return new ProductTypeMutationError("id", ProductTypeMutationErrorCode.INVALID,
                            "The variant-selection move collapses variants '" + clash.getSku()
                                    + "' and '" + variant.getSku()
                                    + "' onto the same typed option combination");
                }
                planned.add(new PendingRecombine(variant, rederived));
            }
        }
        return null;
    }

    /** The verified re-derivation, staged for the post-verdict apply — the variant and its post-move key. */
    private record PendingRecombine(ProductVariant variant, String combinationKey) {
    }

    /** The deferred move carrier — the assignment, its target scope, and the derived position. */
    private record DeferredMove(ProductTypeAttributeAssignment assignment,
                                ProductTypeAttributeScope target, int position) {
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductTypeMutationResult unassignAttributes(UUID productTypeId, List<UUID> attributeIds) {
        // The 2026-11-06 #808 round review (Minor · Stability &
        // Availability): the SAME PESSIMISTIC_WRITE type load the assign
        // path carries — every writer that derives positions from a
        // prior read (updateAssignments' pendingNextPosition,
        // reorderAttributes' park range) serializes on the type row, so
        // no two writers can derive the same position and answer the
        // unique index's raw error instead of the classified one.
        Optional<ProductType> type = productTypeId == null
                ? Optional.empty() : productTypeRepository.findByIdForUpdate(productTypeId);
        if (type.isEmpty()) {
            return ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                    "productTypeId", ProductTypeMutationErrorCode.NOT_FOUND,
                    "No product type found for the id")));
        }
        // Unknown ids are the owner's own honest skip — the payload's
        // empty error list answers the registry's own truth. The
        // 2026-11-06 #808 round review (Major · Data Integrity &
        // Integration): the whole REQUEST is resolved FIRST and its
        // verdict passes BEFORE any assignment is deleted — an
        // unassigned VARIANT_SELECTION assignment would leave the live
        // variant values bound to a soft-deleted assignment and the
        // variant keys computed on the dead schema (a fresh registration
        // under the shrunk schema could then duplicate the combination;
        // the updateAssignments scope-move guard's own law, applied to
        // the unassignment's remove-shape). An assignment carrying live
        // variant values answers the classified INVALID refusal — the
        // variants migrate first, then the assignment goes.
        List<ProductTypeAttributeAssignment> addressedAssignments = new ArrayList<>();
        for (UUID attributeId : attributeIds) {
            findAssignment(productTypeId, attributeId)
                    .ifPresent(addressedAssignments::add);
        }
        if (!addressedAssignments.isEmpty()
                && variantAttributeValues.existsLiveVariantValueForAssignmentsOrOptions(
                        addressedAssignments.stream()
                                .map(ProductTypeAttributeAssignment::getId).toList(),
                        List.of())) {
            return ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                    "id", ProductTypeMutationErrorCode.INVALID,
                    "An assignment referenced by a live variant cannot be unassigned — "
                            + "migrate the variants first")));
        }
        for (ProductTypeAttributeAssignment assignment : addressedAssignments) {
            assignmentRepository.delete(assignment);
        }
        return ProductTypeMutationResult.success(toView(type.get()));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ProductTypeMutationResult reorderAttributes(UUID productTypeId, String attributeType,
                                                       List<AttributeReorderMove> moves) {
        // The 2026-11-06 #808 round review (Minor · Stability &
        // Availability): the SAME PESSIMISTIC_WRITE type load the assign
        // path carries — every writer that derives positions from a
        // prior read (updateAssignments' pendingNextPosition,
        // reorderAttributes' park range) serializes on the type row, so
        // no two writers can derive the same position and answer the
        // unique index's raw error instead of the classified one.
        Optional<ProductType> type = productTypeId == null
                ? Optional.empty() : productTypeRepository.findByIdForUpdate(productTypeId);
        if (type.isEmpty()) {
            return ProductTypeMutationResult.failure(List.of(new ProductTypeMutationError(
                    "productTypeId", ProductTypeMutationErrorCode.NOT_FOUND,
                    "No product type found for the id")));
        }
        List<ProductTypeMutationError> errors = new ArrayList<>();
        if (!"PRODUCT".equals(attributeType) && !"VARIANT".equals(attributeType)) {
            errors.add(new ProductTypeMutationError("type", ProductTypeMutationErrorCode.INVALID,
                    "An attribute bucket is addressed as PRODUCT or VARIANT"));
        }
        if (!errors.isEmpty()) {
            return ProductTypeMutationResult.failure(errors);
        }
        // The bucket's ordered membership (BOTH VARIANT-family scopes
        // — the VARIANT and VARIANT_SELECTION assignments share the
        // variant bucket's order).
        // The 2026-11-06 #808 round review (Major · Functional
        // Correctness): the merged bucket is sorted by the STORED
        // POSITION with a deterministic tie-break — the two per-scope
        // position spaces overlap (each scope numbers 1..k
        // independently), so the repository's scope-major order is NOT
        // the position order; a shift resolved against the scope-major
        // list could move the wrong neighbor, and the no-change verdict
        // compared a scope-major list against dense 1..n positions it
        // never carried. The (position, scope, id) sort pins ONE order
        // the read path shares and the dense rewrite preserves.
        List<ProductTypeAttributeAssignment> bucket = assignmentRepository
                .findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId).stream()
                .filter(assignment -> "PRODUCT".equals(attributeType)
                        ? assignment.getScope() == ProductTypeAttributeScope.PRODUCT
                        : assignment.getScope() != ProductTypeAttributeScope.PRODUCT)
                .sorted(java.util.Comparator
                        .comparingInt(ProductTypeAttributeAssignment::getPosition)
                        .thenComparing(assignment -> assignment.getScope().ordinal())
                        .thenComparing(ProductTypeAttributeAssignment::getId))
                .toList();
        Map<UUID, ProductTypeAttributeAssignment> byAttribute = new LinkedHashMap<>();
        for (ProductTypeAttributeAssignment assignment : bucket) {
            byAttribute.put(assignment.getAttributeDefinitionId(), assignment);
        }
        List<ProductTypeAttributeAssignment> ordered = new ArrayList<>(bucket);
        for (AttributeReorderMove move : moves) {
            if (move.itemId() == null || !byAttribute.containsKey(move.itemId())) {
                errors.add(new ProductTypeMutationError("id", ProductTypeMutationErrorCode.NOT_FOUND,
                        "No assigned attribute found for the id in the addressed bucket"));
                continue;
            }
            int shift = move.sortOrder() == null ? 0 : move.sortOrder();
            int currentIndex = indexOfAttribute(ordered, move.itemId());
            int targetIndex = currentIndex + shift;
            if (targetIndex < 0 || targetIndex >= ordered.size()) {
                errors.add(new ProductTypeMutationError("sortOrder", ProductTypeMutationErrorCode.INVALID,
                        "The relative move leaves the bucket's order"));
                continue;
            }
            ProductTypeAttributeAssignment moved = ordered.remove(currentIndex);
            ordered.add(targetIndex, moved);
        }
        if (!errors.isEmpty()) {
            return ProductTypeMutationResult.failure(errors);
        }
        // The live-rows unique index (product_type_id, scope, position)
        // makes a direct 1..n rewrite collide TRANSIENTLY (a row moving to
        // position 1 shares it with the current holder until that row's own
        // UPDATE executes — the partial unique index sees the intermediate
        // state). The two-phase rewrite keeps EVERY intermediate state
        // unique: phase 1 parks the WHOLE bucket beyond its current maximum,
        // flushes (the 1..max range is now empty), then phase 2 writes the
        // final dense order into the empty range — one transaction,
        // all-or-nothing with the reorder itself. Same repair as the
        // attribute-value reorder's own live-position index.
        boolean changed = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getPosition() != i + 1) {
                changed = true;
                break;
            }
        }
        if (!changed) {
            // The zero-shift idempotent no-op: the resolved order is the
            // stored order — nothing changes, nothing writes.
            return ProductTypeMutationResult.success(toView(type.get()));
        }
        // The 2026-11-05 #808 round-3 review (Major · Functional Correctness,
        // repeat): the VARIANT-family bucket merges TWO per-scope-numbered
        // position spaces, so the merged list can hold MORE members than the
        // largest stored position (two attributes per scope: n=4, max=2).
        // A park range starting at max+1 overlapped the final 1..n range,
        // and Hibernate's update-flush order (NOT the saveAll list order)
        // could transiently duplicate a (product_type_id, scope, position)
        // row against the live unique index. The park starts beyond BOTH
        // the current maximum AND the final range's own end.
        int park = Math.max(
                ordered.stream().mapToInt(ProductTypeAttributeAssignment::getPosition).max().orElse(0),
                ordered.size()) + 1;
        for (ProductTypeAttributeAssignment assignment : ordered) {
            assignment.reposition(park++);
        }
        assignmentRepository.saveAll(ordered);
        assignmentRepository.flush();
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).reposition(i + 1);
        }
        assignmentRepository.saveAll(ordered);
        return ProductTypeMutationResult.success(toView(type.get()));
    }

    /**
     * The addressed assignment — the schema's own (product type,
     * attribute) pair lookup.
     */
    private Optional<ProductTypeAttributeAssignment> findAssignment(UUID productTypeId, UUID attributeId) {
        if (attributeId == null) {
            return Optional.empty();
        }
        return assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId).stream()
                .filter(assignment -> assignment.getAttributeDefinitionId().equals(attributeId))
                .findFirst();
    }

    private static int indexOfAttribute(List<ProductTypeAttributeAssignment> ordered, UUID attributeId) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getAttributeDefinitionId().equals(attributeId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The append position — the owner's own max+1 derivation within
     * the (product type, scope) bucket (the attribute-value slice's
     * own append law; never a client-supplied fact).
     */
    private int nextPosition(UUID productTypeId, ProductTypeAttributeScope scope) {
        return assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId).stream()
                .filter(assignment -> assignment.getScope() == scope)
                .mapToInt(ProductTypeAttributeAssignment::getPosition)
                .max().orElse(0) + 1;
    }

    /** The bounded projection — the product-type write family's own rendering rule. */
    private static ProductTypeView toView(ProductType type) {
        String name = type.getNameEn() != null ? type.getNameEn() : type.getNameAr();
        return new ProductTypeView(type.getId(), name, type.getCode());
    }
}
