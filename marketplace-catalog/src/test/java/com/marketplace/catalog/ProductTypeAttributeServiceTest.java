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
    private ProductTypeAttributeService service;
    private final UUID typeId = UUID.randomUUID();
    private final UUID attributeId = UUID.randomUUID();

    @BeforeEach void setUp() {
        productTypes = mock(ProductTypeRepository.class);
        definitions = mock(CatalogAttributeDefinitionRepository.class);
        options = mock(CatalogAttributeOptionRepository.class);
        assignments = mock(ProductTypeAttributeAssignmentRepository.class);
        service = new ProductTypeAttributeService(productTypes, definitions, options, assignments);
        when(productTypes.findById(typeId)).thenReturn(Optional.of(
                ProductType.create("apparel", "Apparel", "ملابس", null)));
    }

    @Test void variantSelectionRequiresChoiceAndAtLeastTwoOptions() {
        when(definitions.findById(attributeId)).thenReturn(Optional.of(
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
        when(definitions.findById(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("weight", "Weight", "الوزن", CatalogAttributeType.NUMBER, null)));
        assertThatThrownBy(() -> service.assign(typeId, attributeId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("CHOICE");
        verify(assignments, never()).save(any());

        when(definitions.findById(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("size", "Size", "المقاس", CatalogAttributeType.CHOICE, null)));
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(1L);
        assertThatThrownBy(() -> service.assign(typeId, attributeId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("two allowed options");
        verify(assignments, never()).save(any());
    }

    @Test void variantSelectionCannotBeDeclaredOptional() {
        when(definitions.findById(attributeId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("color", "Color", "اللون", CatalogAttributeType.CHOICE, null)));
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(2L);
        assertThatThrownBy(() -> service.assign(typeId, attributeId,
                ProductTypeAttributeScope.VARIANT_SELECTION, false, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("must be required");
        verify(assignments, never()).save(any());
    }

    @Test void duplicateAssignmentAndDuplicatePositionAreRejected() {
        when(definitions.findById(attributeId)).thenReturn(Optional.of(
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
}
