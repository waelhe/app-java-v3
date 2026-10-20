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

class StoreCategoryServiceTest {
    private StoreCategoryRepository repository;
    private StoreCategoryService service;

    @BeforeEach void setUp() {
        repository = mock(StoreCategoryRepository.class);
        service = new StoreCategoryService(repository);
    }

    @Test void registerPersistsStableCodeLabelsAndPosition() {
        when(repository.findByCode("home-essentials")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StoreCategory created = service.register("home-essentials", "Home essentials", "أساسيات المنزل", 10);
        assertThat(created.getId()).isNotNull();
        assertThat(created.getCode()).isEqualTo("home-essentials");
        assertThat(created.getNameEn()).isEqualTo("Home essentials");
        assertThat(created.getNameAr()).isEqualTo("أساسيات المنزل");
        assertThat(created.getPosition()).isEqualTo(10);
        verify(repository).save(created);
    }

    @Test void registerDefaultsAbsentPositionToZero() {
        when(repository.findByCode("grocery")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StoreCategory created = service.register("grocery", "Grocery", "بقالة", null);
        assertThat(created.getPosition()).isZero();
    }

    @Test void duplicateCodeIsRejectedBeforeWrite() {
        StoreCategory existing = StoreCategory.register("home-essentials", "Home essentials", null, 0);
        when(repository.findByCode("home-essentials")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.register("home-essentials", "Essentials", null, null))
                .isInstanceOf(ConflictException.class).hasMessageContaining("home-essentials");
        verify(repository, never()).save(any());
    }

    @Test void invalidCodeOrMissingDisplayNameOrNegativePositionIsRejectedBeforeDatabaseAccess() {
        assertThatThrownBy(() -> service.register("Home Essentials", "Essentials", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lowercase slug");
        assertThatThrownBy(() -> service.register("home-essentials", " ", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
        assertThatThrownBy(() -> service.register("home-essentials", "Essentials", null, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-negative");
        verifyNoInteractions(repository);
    }

    @Test void updateChangesLabelsAndPositionButKeepsStableCode() {
        UUID id = UUID.randomUUID();
        StoreCategory existing = StoreCategory.register("home-essentials", "Home essentials", null, 0);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        StoreCategory updated = service.update(id, "Daily essentials", "أساسيات يومية", 20);
        assertThat(updated.getId()).isEqualTo(existing.getId());
        assertThat(updated.getCode()).isEqualTo("home-essentials");
        assertThat(updated.getNameEn()).isEqualTo("Daily essentials");
        assertThat(updated.getNameAr()).isEqualTo("أساسيات يومية");
        assertThat(updated.getPosition()).isEqualTo(20);
    }

    @Test void updateOmittedPositionKeepsTheStoredOne() {
        UUID id = UUID.randomUUID();
        StoreCategory existing = StoreCategory.register("home-essentials", "Home essentials", null, 7);
        when(repository.findById(id)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertThat(service.update(id, "Daily essentials", null, null).getPosition()).isEqualTo(7);
    }

    @Test void getForConfigurationReturnsTheRequestedDictionaryEntry() {
        UUID id = UUID.randomUUID();
        StoreCategory category = StoreCategory.register("home-essentials", "Home essentials", null, 2);
        when(repository.findById(id)).thenReturn(Optional.of(category));

        assertThat(service.getForConfiguration(id)).isSameAs(category);
        verify(repository).findById(id);
    }

    @Test void unknownCategoryIsNotReadUpdatedOrDeleted() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(missing)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(missing, "New label", null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.remove(missing)).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
        verify(repository, never()).delete(any(StoreCategory.class));
    }

    @Test void listUsesRepositoryPositionOrder() {
        List<StoreCategory> categories = List.of(
                StoreCategory.register("grocery", "Grocery", "بقالة", 0),
                StoreCategory.register("home-essentials", "Home essentials", "أساسيات المنزل", 10));
        when(repository.findAllByOrderByPositionAsc()).thenReturn(categories);
        assertThat(service.listForConfiguration()).containsExactlyElementsOf(categories);
    }

    @Test void removeMapsThroughTheSoftDeleteWrite() {
        StoreCategory existing = StoreCategory.register("home-essentials", "Home essentials", null, 0);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        service.remove(existing.getId());
        verify(repository).delete(existing);
    }
}
