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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogAttributeServiceTest {
    private CatalogAttributeDefinitionRepository definitions;
    private CatalogAttributeOptionRepository options;
    private CatalogAttributeService service;

    @BeforeEach void setUp() {
        definitions = mock(CatalogAttributeDefinitionRepository.class);
        options = mock(CatalogAttributeOptionRepository.class);
        service = new CatalogAttributeService(definitions, options);
    }

    @Test void registerDefinitionPersistsReusableTypedAttribute() {
        when(definitions.findByCode("material")).thenReturn(Optional.empty());
        when(definitions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CatalogAttributeDefinition created = service.registerDefinition("material", "Material", "الخامة",
                CatalogAttributeType.CHOICE, null);
        assertThat(created.getCode()).isEqualTo("material");
        assertThat(created.getValueType()).isEqualTo(CatalogAttributeType.CHOICE);
        verify(definitions).save(created);
    }

    @Test void registerOptionOnlyAcceptsChoiceDefinitionsAndKeepsStableOrder() {
        UUID definitionId = UUID.randomUUID();
        when(definitions.findById(definitionId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("material", "Material", "الخامة", CatalogAttributeType.CHOICE, null)));
        when(options.findByAttributeDefinitionIdAndCode(definitionId, "cotton")).thenReturn(Optional.empty());
        when(options.existsByAttributeDefinitionIdAndPosition(definitionId, 0)).thenReturn(false);
        when(options.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CatalogAttributeOption option = service.registerOption(definitionId, "cotton", "Cotton", "قطن", 0);
        assertThat(option.getAttributeDefinitionId()).isEqualTo(definitionId);
        assertThat(option.getPosition()).isZero();
        verify(options).save(option);
    }

    @Test void nonChoiceDefinitionCannotReceiveAllowedValues() {
        UUID definitionId = UUID.randomUUID();
        when(definitions.findById(definitionId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("weight", "Weight", "الوزن", CatalogAttributeType.NUMBER, null)));
        assertThatThrownBy(() -> service.registerOption(definitionId, "light", "Light", "خفيف", 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("CHOICE");
        verifyNoInteractions(options);
    }

    @Test void duplicateOptionCodeOrPositionIsRejectedBeforeWrite() {
        UUID definitionId = UUID.randomUUID();
        when(definitions.findById(definitionId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("color", "Color", "اللون", CatalogAttributeType.CHOICE, null)));
        when(options.findByAttributeDefinitionIdAndCode(definitionId, "red"))
                .thenReturn(Optional.of(CatalogAttributeOption.create(definitionId, "red", "Red", "أحمر", 0)));
        assertThatThrownBy(() -> service.registerOption(definitionId, "red", "Red", "أحمر", 1))
                .isInstanceOf(ConflictException.class);
        when(options.findByAttributeDefinitionIdAndCode(definitionId, "blue")).thenReturn(Optional.empty());
        when(options.existsByAttributeDefinitionIdAndPosition(definitionId, 0)).thenReturn(true);
        assertThatThrownBy(() -> service.registerOption(definitionId, "blue", "Blue", "أزرق", 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("position");
        verify(options, never()).save(any());
    }
}
