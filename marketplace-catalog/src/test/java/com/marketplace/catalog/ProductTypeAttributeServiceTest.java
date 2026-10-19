package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(2);
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
        when(options.countByAttributeDefinitionId(attributeId)).thenReturn(1);
        assertThatThrownBy(() -> service.assign(typeId, attributeId, ProductTypeAttributeScope.VARIANT_SELECTION, true, 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("two allowed options");
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
}
