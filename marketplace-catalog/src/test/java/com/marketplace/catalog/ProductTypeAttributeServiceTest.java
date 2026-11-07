package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductTypeAttributeServiceTest {
    private ProductTypeRepository productTypes;
    private CatalogAttributeDefinitionRepository definitions;
    private CatalogAttributeOptionRepository options;
    private ProductTypeAttributeAssignmentRepository assignments;
    private ProductVariantRepository variantRepository;
    private ProductAttributeValueRepository productAttributeValues;
    private ProductVariantAttributeValueRepository variantAttributeValues;
    private ProductTypeAttributeService service;
    private final UUID typeId = UUID.randomUUID();
    private final UUID attributeId = UUID.randomUUID();

    @BeforeEach void setUp() {
        productTypes = mock(ProductTypeRepository.class);
        definitions = mock(CatalogAttributeDefinitionRepository.class);
        options = mock(CatalogAttributeOptionRepository.class);
        assignments = mock(ProductTypeAttributeAssignmentRepository.class);
        // The 2026-11-06 #808 round-3: the published-shelf variant guard
        // answers NO variants by default (each test overrides).
        variantRepository = mock(ProductVariantRepository.class);
        when(variantRepository.existsPublishedShelfVariantForType(any())).thenReturn(false);
        // The 2026-11-07 #808 round-7: the required-PRODUCT guard answers
        // NO missing-value products by default (each test overrides).
        productAttributeValues = mock(ProductAttributeValueRepository.class);
        when(productAttributeValues.existsPublishedShelfProductMissingValueFor(any(), any())).thenReturn(false);
        // The 2026-11-07 #808 round-9: the required-VARIANT guard answers
        // NO missing-value variants by default (each test overrides).
        variantAttributeValues = mock(ProductVariantAttributeValueRepository.class);
        when(variantAttributeValues.existsPublishedShelfVariantMissingValueFor(any(), any())).thenReturn(false);
        service = new ProductTypeAttributeService(productTypes, definitions, options, assignments,
                variantRepository, productAttributeValues, variantAttributeValues);
        // The 2026-11-06 #808 round (#10): the reference-creating assign
        // path rides the LOCKED type read (the deleteProductType
        // FOR-UPDATE wall's own pairing; the 2026-11-07 round-4 review
        // widened it to the WRITE lock — the assignAttributes twin's own
        // discipline, pairing with publishOwnedProduct's FOR-SHARE read)
        // — the stub rides the locked read. The round's follow-up (Major
        // · Stability): the READ-ONLY list path keeps the PLAIN findById
        // — PostgreSQL rejects FOR SHARE inside a read-only transaction,
        // so the list would answer a raw 500.
        when(productTypes.findById(typeId)).thenReturn(Optional.of(
                ProductType.create("apparel", "Apparel", "ملابس", null)));
        when(productTypes.findByIdForUpdate(typeId)).thenReturn(Optional.of(
                ProductType.create("apparel", "Apparel", "ملابس", null)));
    }

    @Test void listReadsTheTypeWithoutTheShareLock() {
        // The 2026-11-06 #808 round review (Major · Stability): the
        // read-only list path keeps the PLAIN findById — PostgreSQL
        // rejects FOR SHARE inside a read-only transaction, and the list
        // would answer a raw 500 instead of the roster.
        service.list(typeId);

        verify(productTypes).findById(typeId);
        verify(productTypes, never()).findByIdForShare(any());
        verify(productTypes, never()).findByIdForUpdate(any());
    }

    @Test void assignTakesTheTypeWriteLockBeforeTheGuards() {
        // The 2026-11-07 #808 round-4 review (Major · Data Integrity &
        // Integration): the assign path rides the WRITE lock on the type
        // row — pairing with publishOwnedProduct's FOR-SHARE read of the
        // same row (the publication's assignment reads and the assign's
        // published-shelf guard serialize; the registration's FOR-SHARE
        // read waits for this writer too).
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("material", "Material", "الخامة", CatalogAttributeType.TEXT, null)));
        when(assignments.existsByProductTypeIdAndAttributeDefinitionId(typeId, attributeId)).thenReturn(false);
        when(assignments.existsByProductTypeIdAndScopeAndPosition(typeId, ProductTypeAttributeScope.PRODUCT, 0)).thenReturn(false);
        when(assignments.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.assign(typeId, attributeId, ProductTypeAttributeScope.PRODUCT, false, 0);

        verify(productTypes).findByIdForUpdate(typeId);
        verify(productTypes, never()).findByIdForShare(any());
    }

    @Test void variantSelectionRequiresChoiceAndAtLeastTwoOptions() {
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("color", "Color", "اللون", CatalogAttributeType.CHOICE, null)));
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(2L);
        when(assignments.existsByProductTypeIdAndAttributeDefinitionId(typeId, attributeId)).thenReturn(false);
        when(assignments.existsByProductTypeIdAndScopeAndPosition(typeId, ProductTypeAttributeScope.VARIANT_SELECTION, 0)).thenReturn(false);
        when(assignments.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeAttributeAssignment assigned = service.assign(typeId, attributeId,
                ProductTypeAttributeScope.VARIANT_SELECTION, true, 0);
        assertThat(assigned.getScope()).isEqualTo(ProductTypeAttributeScope.VARIANT_SELECTION);
        assertThat(assigned.isRequired()).isTrue();
        verify(assignments).save(assigned);
    }

    @Test void variantSelectionRejectsNonChoiceAndInsufficientOptions() {
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("weight", "Weight", "الوزن", CatalogAttributeType.NUMBER, null)));
        assertThatThrownBy(() -> service.assign(typeId, attributeId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("CHOICE");
        verify(assignments, never()).save(any());

        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("size", "Size", "المقاس", CatalogAttributeType.CHOICE, null)));
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(1L);
        assertThatThrownBy(() -> service.assign(typeId, attributeId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("two allowed options");
        verify(assignments, never()).save(any());
    }

    @Test void variantSelectionCannotBeDeclaredOptional() {
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("color", "Color", "اللون", CatalogAttributeType.CHOICE, null)));
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(2L);
        assertThatThrownBy(() -> service.assign(typeId, attributeId,
                ProductTypeAttributeScope.VARIANT_SELECTION, false, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("must be required");
        verify(assignments, never()).save(any());
    }

    @Test void duplicateAssignmentAndDuplicatePositionAreRejected() {
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("material", "Material", "الخامة", CatalogAttributeType.TEXT, null)));
        when(assignments.existsByProductTypeIdAndAttributeDefinitionId(typeId, attributeId)).thenReturn(true);
        assertThatThrownBy(() -> service.assign(typeId, attributeId, ProductTypeAttributeScope.PRODUCT, false, 0))
                .isInstanceOf(ConflictException.class);
        when(assignments.existsByProductTypeIdAndAttributeDefinitionId(typeId, attributeId)).thenReturn(false);
        when(assignments.existsByProductTypeIdAndScopeAndPosition(typeId, ProductTypeAttributeScope.PRODUCT, 0))
                .thenReturn(true);
        assertThatThrownBy(() -> service.assign(typeId, attributeId, ProductTypeAttributeScope.PRODUCT, false, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("position");
        verify(assignments, never()).save(any());
    }

    @Test void listRendersTheVariantFamilyTailInTheCanonicalPositionFirstOrder() {
        // The 2026-11-06 #808 round (Minor · Functional Correctness): the
        // merged VARIANT-family bucket's canonical order is POSITION-first
        // (scope as the tie-break) — the SAME order the write service's
        // reorderAttributes reads and writes — so a reorder round-trips
        // faithfully through the read API. The repository's own
        // (scope, position) order reads the two per-scope number spaces
        // scope-at-a-time; the read API must not leak that order.
        java.util.UUID prodDef = java.util.UUID.randomUUID();
        java.util.UUID varDef = java.util.UUID.randomUUID();
        java.util.UUID selDef = java.util.UUID.randomUUID();
        ProductTypeAttributeAssignment product =
                ProductTypeAttributeAssignment.create(typeId, prodDef, ProductTypeAttributeScope.PRODUCT, true, 1);
        ProductTypeAttributeAssignment variant =
                ProductTypeAttributeAssignment.create(typeId, varDef, ProductTypeAttributeScope.VARIANT, false, 1);
        ProductTypeAttributeAssignment selection =
                ProductTypeAttributeAssignment.create(typeId, selDef, ProductTypeAttributeScope.VARIANT_SELECTION, true, 2);
        // The repository's scope-at-a-time order: PRODUCT first, then
        // VARIANT (pos 1), then VARIANT_SELECTION (pos 2) — the stored
        // interleaved state (variant=1, selection=2) is what a prior
        // reorder wrote.
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(typeId))
                .thenReturn(java.util.List.of(product, variant, selection));

        java.util.List<ProductTypeAttributeAssignment> listed = service.list(typeId);

        // The PRODUCT head keeps its place; the variant-family tail rides
        // the canonical (position, scope) order — here variant (pos 1)
        // before selection (pos 2), the round-trip-stable order.
        assertThat(listed).containsExactly(product, variant, selection);
        // The discriminator case: a stored state where the SELECTION holds
        // the LOWER position (selection=1, variant=2) must list the
        // selection FIRST in the tail — the repository order would have
        // listed the variant first.
        ProductTypeAttributeAssignment variantHigh =
                ProductTypeAttributeAssignment.create(typeId, varDef, ProductTypeAttributeScope.VARIANT, false, 2);
        ProductTypeAttributeAssignment selectionLow =
                ProductTypeAttributeAssignment.create(typeId, selDef, ProductTypeAttributeScope.VARIANT_SELECTION, true, 1);
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(typeId))
                .thenReturn(java.util.List.of(product, variantHigh, selectionLow));

        java.util.List<ProductTypeAttributeAssignment> relisted = service.list(typeId);

        assertThat(relisted).containsExactly(product, selectionLow, variantHigh);
    }

    @Test void listBucketsTheProductScopeAheadOfTheVariantFamily() {
        // The 2026-11-06 #808 round-3 review (Minor · Functional
        // Correctness): the bucket-major order — MORE THAN ONE PRODUCT
        // assignment plus an interleaved VARIANT family: the previous
        // position-major sort interleaved the buckets (PRODUCT@1,
        // VARIANT@1, PRODUCT@2...), so the read surface addressed
        // neighbors the reorder path (the bucket-major resolution)
        // never resolved.
        ProductTypeAttributeAssignment productOne =
                ProductTypeAttributeAssignment.create(typeId, UUID.randomUUID(), ProductTypeAttributeScope.PRODUCT, true, 1);
        ProductTypeAttributeAssignment productTwo =
                ProductTypeAttributeAssignment.create(typeId, UUID.randomUUID(), ProductTypeAttributeScope.PRODUCT, false, 2);
        ProductTypeAttributeAssignment variantOne =
                ProductTypeAttributeAssignment.create(typeId, UUID.randomUUID(), ProductTypeAttributeScope.VARIANT, false, 1);
        // The repository's scope-at-a-time order (what the stored state
        // answers): PRODUCT bucket, then the VARIANT family.
        when(assignments.findAllByProductTypeIdOrderByScopeAscPositionAsc(typeId))
                .thenReturn(java.util.List.of(productOne, productTwo, variantOne));

        java.util.List<ProductTypeAttributeAssignment> listed = service.list(typeId);

        // The PRODUCT bucket keeps its OWN (position, ...) order ahead of
        // the VARIANT family — never the interleaved PRODUCT@1, VARIANT@1,
        // PRODUCT@2 the position-major sort answered.
        assertThat(listed).containsExactly(productOne, productTwo, variantOne);
    }

    @Test void variantSelectionIsRefusedWhileAPublishedProductCarriesAnyLiveVariant() {
        // The 2026-11-06 #808 round-3 review (Major · Data Integrity &
        // Integration), widened by the round-5 review: a published (or
        // suspended — reactivate() returns it to ACTIVE without re-running
        // the publication gate) product with ANY live variant — the TYPED
        // row of round-3, and the round-5 scenario's FREE-FORM NULL-KEY
        // row (a selection-free type publishes with one, and the assign
        // would leave it short of the new selection's required value with
        // no gate to surface it) — refuses the assign; the variants
        // migrate first.
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("color", "Color", "اللون", CatalogAttributeType.CHOICE, null)));
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(2L);
        when(variantRepository.existsPublishedShelfVariantForType(typeId)).thenReturn(true);

        assertThatThrownBy(() -> service.assign(typeId, attributeId,
                ProductTypeAttributeScope.VARIANT_SELECTION, true, 0))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("migrate the variants first");
        verify(assignments, never()).save(any());
    }

    @Test void requiredProductAssignmentIsRefusedWhileAPublishedProductLacksTheValue() {
        // The 2026-11-07 #808 round-7 review: a required PRODUCT-scope
        // assignment lands on a published (or suspended) product without
        // its value as a conformance hole the shelf never re-passes the
        // publication gate to surface — the products migrate first (the
        // probe walks the definition's own live assignments, never the
        // variant probe).
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("material", "Material", "الخامة", CatalogAttributeType.TEXT, null)));
        when(productAttributeValues.existsPublishedShelfProductMissingValueFor(typeId, attributeId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.assign(typeId, attributeId,
                ProductTypeAttributeScope.PRODUCT, true, 0))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("assign it as optional")
                .hasMessageContaining("promote it to required");
        verify(assignments, never()).save(any());
    }

    @Test void requiredVariantAssignmentIsRefusedWhileAPublishedProductsLiveVariantLacksTheValue() {
        // The 2026-11-07 #808 round-9 review: a required VARIANT-scope
        // assignment lands on a published product's LIVE variant without
        // its value as the same conformance hole the PRODUCT guard closes
        // — the VALUE-ABSENCE probe (never the bare published-variant
        // shape) refuses the assign; the variants migrate first.
        when(definitions.findByIdForShare(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("size", "Size", "المقاس", CatalogAttributeType.TEXT, null)));
        when(variantAttributeValues.existsPublishedShelfVariantMissingValueFor(typeId, attributeId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.assign(typeId, attributeId,
                ProductTypeAttributeScope.VARIANT, true, 0))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("assign it as optional")
                .hasMessageContaining("promote it to required");
        verify(assignments, never()).save(any());
    }

    @Test void setRequiredPromotesAnOptionalProductAssignmentOnceTheShelfIsCovered() {
        // The 2026-11-07 #808 round-10 review: the migration path's
        // executable half — the assignment lands OPTIONAL (the assign
        // guard's own refusal names this route), the owners store the
        // values, and the promotion passes the SAME fail-closed probe
        // over the EXISTING live assignment's coverage.
        ProductTypeAttributeAssignment optional = ProductTypeAttributeAssignment.create(
                typeId, attributeId, ProductTypeAttributeScope.PRODUCT, false, 0);
        when(assignments.findProductTypeIdById(optional.getId())).thenReturn(Optional.of(typeId));
        when(assignments.findById(optional.getId())).thenReturn(Optional.of(optional));
        when(assignments.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeAttributeAssignment promoted = service.setRequired(optional.getId(), true);

        assertThat(promoted.isRequired()).isTrue();
        verify(assignments).save(promoted);
        verify(productTypes).findByIdForUpdate(typeId);
    }

    @Test void setRequiredRefusesTheProductPromotionWhileAPublishedProductStillLacksTheValue() {
        // The round-10 law: the promotion is the assign guard's own
        // fail-closed verdict — a published-shelf product still missing
        // the value refuses the promotion (never a conformance hole).
        ProductTypeAttributeAssignment optional = ProductTypeAttributeAssignment.create(
                typeId, attributeId, ProductTypeAttributeScope.PRODUCT, false, 0);
        when(assignments.findProductTypeIdById(optional.getId())).thenReturn(Optional.of(typeId));
        when(assignments.findById(optional.getId())).thenReturn(Optional.of(optional));
        when(productAttributeValues.existsPublishedShelfProductMissingValueFor(typeId, attributeId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.setRequired(optional.getId(), true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("the owners store the values first")
                .hasMessageContaining("then the promotion passes");
        verify(assignments, never()).save(any());
    }

    @Test void setRequiredRefusesTheVariantPromotionWhileALiveVariantStillLacksTheValue() {
        // The round-9 mirror's promotion shape: a live variant of a
        // published product still missing the value refuses the promotion
        // — the variants re-register with the values first.
        ProductTypeAttributeAssignment optional = ProductTypeAttributeAssignment.create(
                typeId, attributeId, ProductTypeAttributeScope.VARIANT, false, 0);
        when(assignments.findProductTypeIdById(optional.getId())).thenReturn(Optional.of(typeId));
        when(assignments.findById(optional.getId())).thenReturn(Optional.of(optional));
        when(variantAttributeValues.existsPublishedShelfVariantMissingValueFor(typeId, attributeId))
                .thenReturn(true);

        assertThatThrownBy(() -> service.setRequired(optional.getId(), true))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("re-register with the values first");
        verify(assignments, never()).save(any());
    }

    @Test void setRequiredKeepsTheVariantSelectionEveryVariantLaw() {
        // The assign path's own law (VARIANT_SELECTION must be required)
        // holds on the promotion route: the flag is not demotable.
        ProductTypeAttributeAssignment selection = ProductTypeAttributeAssignment.create(
                typeId, attributeId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0);
        when(assignments.findProductTypeIdById(selection.getId())).thenReturn(Optional.of(typeId));
        when(assignments.findById(selection.getId())).thenReturn(Optional.of(selection));

        assertThatThrownBy(() -> service.setRequired(selection.getId(), false))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("must be required for every variant");
        verify(assignments, never()).save(any());
    }

    @Test void setRequiredDemotesWithoutTheCoverageProbe() {
        // The demotion is a relaxation that carries no conformance hole
        // (the values stay; the publication gate simply stops demanding
        // them) — no probe runs, the flag just loosens.
        ProductTypeAttributeAssignment required = ProductTypeAttributeAssignment.create(
                typeId, attributeId, ProductTypeAttributeScope.PRODUCT, true, 0);
        when(assignments.findProductTypeIdById(required.getId())).thenReturn(Optional.of(typeId));
        when(assignments.findById(required.getId())).thenReturn(Optional.of(required));
        when(assignments.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProductTypeAttributeAssignment demoted = service.setRequired(required.getId(), false);

        assertThat(demoted.isRequired()).isFalse();
        verify(productAttributeValues, never()).existsPublishedShelfProductMissingValueFor(any(), any());
        verify(variantAttributeValues, never()).existsPublishedShelfVariantMissingValueFor(any(), any());
    }

    @Test void setRequiredAnswersTheHonest404ForAnUnknownAssignment() {
        UUID unknown = UUID.randomUUID();
        when(assignments.findProductTypeIdById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setRequired(unknown, true))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
        verify(assignments, never()).save(any());
    }

    @Test void setRequiredLocksTheTypeBeforeLoadingTheAssignment() {
        // The 2026-11-07 #808 round-11 review (Minor · Data Integrity &
        // Integration): the owning type resolves FIRST (the projection
        // loads no entity), the type write lock is taken, and ONLY THEN
        // the assignment loads — a concurrent updateAssignments scope
        // move that committed under the lock is what the post-lock read
        // sees, never a stale pre-lock scope from the persistence
        // context.
        ProductTypeAttributeAssignment optional = ProductTypeAttributeAssignment.create(
                typeId, attributeId, ProductTypeAttributeScope.PRODUCT, false, 0);
        when(assignments.findProductTypeIdById(optional.getId())).thenReturn(Optional.of(typeId));
        when(assignments.findById(optional.getId())).thenReturn(Optional.of(optional));
        when(assignments.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setRequired(optional.getId(), true);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(assignments, productTypes);
        inOrder.verify(assignments).findProductTypeIdById(optional.getId());
        inOrder.verify(productTypes).findByIdForUpdate(typeId);
        inOrder.verify(assignments).findById(optional.getId());
        inOrder.verify(assignments).save(optional);
    }
}
