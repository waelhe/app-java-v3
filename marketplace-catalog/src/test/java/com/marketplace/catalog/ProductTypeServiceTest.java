package com.marketplace.catalog;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
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

class ProductTypeServiceTest {
    private ProductTypeRepository repository;
    private ProductRepository productRepository;
    private ProductTypeAttributeAssignmentRepository assignmentRepository;
    private ProductTypeService service;

    @BeforeEach void setUp() {
        repository = mock(ProductTypeRepository.class);
        productRepository = mock(ProductRepository.class);
        assignmentRepository = mock(ProductTypeAttributeAssignmentRepository.class);
        // The 2026-11-07 #808 round-19: the live-references guard's own
        // probes (the remove law's twin repositories).
        when(productRepository.existsByProductTypeId(any())).thenReturn(false);
        when(assignmentRepository.existsByProductTypeId(any())).thenReturn(false);
        service = new ProductTypeService(repository, productRepository, assignmentRepository);
    }

    @Test void registerPersistsStableCodeAndLocalizedLabels() {
        when(repository.findByCode("home-appliances")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProductType created = service.register("home-appliances", "Home appliances", "الأجهزة المنزلية", "Reusable type for physical goods.");
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo("home-appliances");
        assertThat(created.getNameEn()).isEqualTo("Home appliances");
        assertThat(created.getNameAr()).isEqualTo("الأجهزة المنزلية");
        verify(repository).save(created);
    }

    @Test void duplicateCodeIsRejectedBeforeWrite() {
        ProductType existing = ProductType.create("home-appliances", "Home appliances", null, null);
        when(repository.findByCode("home-appliances")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.register("home-appliances", "Appliances", null, null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("home-appliances");
        verify(repository, never()).save(any());
    }

    @Test void invalidCodeOrMissingDisplayNameIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.register("Home Appliances", "Appliances", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lowercase slug");
        assertThatThrownBy(() -> service.register("home-appliances", " ", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        verifyNoInteractions(repository);
    }

    @Test void updateChangesLabelsButKeepsStableCode() {
        UUID id = UUID.randomUUID();
        ProductType existing = ProductType.create("home-appliances", "Home appliances", null, null);
        // The 2026-11-07 #808 round-20: the update rides the locked read.
        when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ProductType updated = service.update(id, "Small appliances", "أجهزة صغيرة", "Updated.");
        assertThat(updated.getId()).isEqualTo(existing.getId());
        assertThat(updated.getCode()).isEqualTo("home-appliances");
        assertThat(updated.getNameEn()).isEqualTo("Small appliances");
        assertThat(updated.getDescription()).isEqualTo("Updated.");
    }

    @Test void unknownTypeIsNotUpdatedOrDeleted() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(missing, "New label", null, null)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.remove(missing)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any(ProductType.class));
    }

    @Test void listUsesRepositoryCodeOrder() {
        List<ProductType> types = List.of(
                ProductType.create("apparel", "Apparel", "ملابس", null),
                ProductType.create("home-appliances", "Home appliances", "الأجهزة المنزلية", null));
        when(repository.findAllByOrderByCodeAsc()).thenReturn(types);
        assertThat(service.listForConfiguration()).containsExactlyElementsOf(types);
    }

    @org.junit.jupiter.api.Test
    void removeRefusesWhileLiveReferencesHoldTheType() {
        // The 2026-11-07 #808 round-19 review (Minor · Data Integrity &
        // Integration): the REST delete owns the GraphQL surface's
        // deleteProductType law — the write lock BEFORE the
        // live-references guard, the classified conflict, no cascade.
        ProductType type = ProductType.create("used", "Used", null, null);
        when(repository.findByIdForUpdate(type.getId())).thenReturn(Optional.of(type));
        when(productRepository.existsByProductTypeId(type.getId())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.remove(type.getId()))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("live product or attribute-assignment references");
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).delete(any());
    }

    @org.junit.jupiter.api.Test
    void removeRefusesWhileAnAssignmentStillHoldsTheType() {
        ProductType type = ProductType.create("used", "Used", null, null);
        when(repository.findByIdForUpdate(type.getId())).thenReturn(Optional.of(type));
        when(assignmentRepository.existsByProductTypeId(type.getId())).thenReturn(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.remove(type.getId()))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).delete(any());
    }

    @org.junit.jupiter.api.Test
    void removeTakesTheWriteLockBeforeTheReferenceProbeThenDeletes() {
        // The lock order (the B21 discipline): FOR UPDATE first, then
        // the probes, then the delete — InOrder pins it.
        ProductType type = ProductType.create("unused", "Unused", null, null);
        when(repository.findByIdForUpdate(type.getId())).thenReturn(Optional.of(type));

        service.remove(type.getId());

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository, productRepository,
                assignmentRepository);
        order.verify(repository).findByIdForUpdate(type.getId());
        order.verify(productRepository).existsByProductTypeId(type.getId());
        order.verify(assignmentRepository).existsByProductTypeId(type.getId());
        order.verify(repository).delete(type);
    }

    @org.junit.jupiter.api.Test
    void removeAnswersTheNotFoundForTheUnknownId() {
        UUID unknown = UUID.randomUUID();
        when(repository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.remove(unknown))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
    }

}
