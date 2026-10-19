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
import static org.mockito.Mockito.*;

class ProductTypeServiceTest {
    private ProductTypeRepository repository;
    private ProductTypeService service;

    @BeforeEach void setUp() {
        repository = mock(ProductTypeRepository.class);
        service = new ProductTypeService(repository);
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
        when(repository.findById(id)).thenReturn(Optional.of(existing));
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
}
