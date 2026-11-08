package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatalogAttributeServiceTest {
    private CatalogAttributeDefinitionRepository definitions;
    private CatalogAttributeOptionRepository options;
    private ProductVariantAttributeValueRepository variantValues;
    private ProductAttributeValueRepository productValues;
    private jakarta.persistence.EntityManager entityManager;
    private CatalogAttributeService service;

    @BeforeEach void setUp() {
        definitions = mock(CatalogAttributeDefinitionRepository.class);
        options = mock(CatalogAttributeOptionRepository.class);
        // The 2026-11-07 #808 round-20: the remove law's reference walls.
        variantValues = mock(ProductVariantAttributeValueRepository.class);
        productValues = mock(ProductAttributeValueRepository.class);
        when(variantValues.existsByOptionId(any())).thenReturn(false);
        when(productValues.existsLiveProductValueForOption(any())).thenReturn(false);
        // The remove law's post-lock re-read answers ALIVE by default.
        lenient().when(options.existsById(any())).thenReturn(true);
        // The 2026-11-08 #808 round-21: the refresh-under-lock contract's own
        // mock (the OrdersService precedent — a no-op refresh).
        entityManager = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        service = new CatalogAttributeService(definitions, options, variantValues, productValues,
                entityManager);
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
        when(definitions.findByIdForUpdate(definitionId)).thenReturn(Optional.of(
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
        when(definitions.findByIdForUpdate(definitionId)).thenReturn(Optional.of(
                CatalogAttributeDefinition.create("weight", "Weight", "الوزن", CatalogAttributeType.NUMBER, null)));
        assertThatThrownBy(() -> service.registerOption(definitionId, "light", "Light", "خفيف", 0))
                .isInstanceOf(ConflictException.class).hasMessageContaining("CHOICE");
        verifyNoInteractions(options);
    }

    @Test void duplicateOptionCodeOrPositionIsRejectedBeforeWrite() {
        UUID definitionId = UUID.randomUUID();
        when(definitions.findByIdForUpdate(definitionId)).thenReturn(Optional.of(
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

    @org.junit.jupiter.api.Test
    void removeOptionRefusesAnOptionReferencedByALiveProduct() {
        // The 2026-11-08 #808 round-21 review (nitpick): the PRODUCT-value
        // wall's own pin — the variant check passes (false) and the product
        // check fires (true), the remove answers the ConflictException and
        // the delete never happens (removing the product wall from
        // removeOption would otherwise pass the suite silently).
        CatalogAttributeDefinition parent = CatalogAttributeDefinition.create(
                "color", "Color", null, CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption used = CatalogAttributeOption.create(parent.getId(), "red", "Red", null, 1);
        when(options.findById(used.getId())).thenReturn(Optional.of(used));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(variantValues.existsByOptionId(used.getId())).thenReturn(false);
        when(productValues.existsLiveProductValueForOption(used.getId())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.removeOption(used.getId()))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("live product");
        org.mockito.Mockito.verify(options, org.mockito.Mockito.never()).delete(any());
    }

    @org.junit.jupiter.api.Test
    void removeOptionRefusesAnOptionReferencedByALiveVariant() {
        // The 2026-11-07 #808 round-20 review (Major · Data Integrity &
        // Integration): the REST remove rides the SPI delete's own law —
        // the definition lock, the live-reference walls, and the post-lock
        // re-read (never the bare find-and-delete).
        CatalogAttributeDefinition parent = CatalogAttributeDefinition.create(
                "color", "Color", null, CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption used = CatalogAttributeOption.create(parent.getId(), "red", "Red", null, 1);
        when(options.findById(used.getId())).thenReturn(Optional.of(used));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(variantValues.existsByOptionId(used.getId())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.removeOption(used.getId()))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("live variant");
        org.mockito.Mockito.verify(options, org.mockito.Mockito.never()).delete(any());
    }

    @org.junit.jupiter.api.Test
    void removeOptionTakesTheDefinitionLockAndReReadsBeforeDeleting() {
        CatalogAttributeDefinition parent = CatalogAttributeDefinition.create(
                "color", "Color", null, CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption victim = CatalogAttributeOption.create(parent.getId(), "red", "Red", null, 1);
        when(options.findById(victim.getId())).thenReturn(Optional.of(victim));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));

        service.removeOption(victim.getId());

        // The 2026-11-08 #808 round-22 review (nitpick): the under-lock
        // contract pins the WHOLE ladder — the definition lock, the
        // honest existence re-check, the refresh-under-lock
        // (PESSIMISTIC_WRITE, the round-21 stale-@Version closure), and
        // only then the delete. A reorder that swaps the refresh and
        // the delete (or drops the refresh) must fail here, not ride a
        // green suite silently.
        // The 2026-11-08 #808 round-23 review (nitpick): the reference
        // probes join the ladder — BOTH walls (the live-variant and the
        // live-product probes) sit AFTER the refresh and BEFORE the
        // delete; moving them ahead of the lock reopens the concurrency
        // window the walls exist to close.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                definitions, options, entityManager, variantValues, productValues);
        order.verify(definitions).findByIdForUpdate(parent.getId());
        order.verify(options).existsById(victim.getId());
        order.verify(entityManager).refresh(victim,
                jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        order.verify(variantValues).existsByOptionId(victim.getId());
        order.verify(productValues).existsLiveProductValueForOption(victim.getId());
        order.verify(options).delete(victim);
    }

    @org.junit.jupiter.api.Test
    void removeOptionAnswersTheHonestNotFoundWhenTheOptionDiedUnderTheLock() {
        CatalogAttributeDefinition parent = CatalogAttributeDefinition.create(
                "color", "Color", null, CatalogAttributeType.CHOICE, null);
        CatalogAttributeOption victim = CatalogAttributeOption.create(parent.getId(), "red", "Red", null, 1);
        when(options.findById(victim.getId())).thenReturn(Optional.of(victim));
        when(definitions.findByIdForUpdate(parent.getId())).thenReturn(Optional.of(parent));
        when(options.existsById(victim.getId())).thenReturn(false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.removeOption(victim.getId()))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
        org.mockito.Mockito.verify(options, org.mockito.Mockito.never()).delete(any());
    }

}
