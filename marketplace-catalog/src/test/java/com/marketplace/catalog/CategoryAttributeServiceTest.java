package com.marketplace.catalog;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2 (yelp-level plan §5 — the business page): the category-attribute
 * registry's service guards — the reference-data lifecycle law
 * («السمات بيانات»): the identity pair (category, code) is free on
 * registration and immutable on amendment, the position allocates
 * max+1 (the W1 lesson), the category must exist, and the reads serve
 * both keys (id and the stable code).
 */
class CategoryAttributeServiceTest {

    private CategoryAttributeRepository repository;
    private CategoryRepository categoryRepository;
    private CategoryAttributeService service;
    private final Authentication admin = new TestingAuthenticationToken(
            "admin", "n/a", "ROLE_ADMIN");

    private final UUID categoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(CategoryAttributeRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        service = new CategoryAttributeService(repository, categoryRepository);
        when(categoryRepository.findById(categoryId))
                .thenReturn(Optional.of(Category.create("stay", "Stay", "إقامة", 1)));
    }

    private CategoryAttribute saved(int position) {
        return CategoryAttribute.create(categoryId, "wifi", "Wi-Fi", "واي فاي",
                CategoryAttributeType.BOOLEAN, position);
    }

    @Test
    void register_allocatesMaxPlusOne() {
        when(repository.findByCategoryIdAndCode(categoryId, "wifi")).thenReturn(Optional.empty());
        when(repository.findMaxPositionByCategoryId(categoryId)).thenReturn(Optional.of(3));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CategoryAttribute registered = service.register(categoryId, "wifi", "Wi-Fi", "واي فاي",
                CategoryAttributeType.BOOLEAN);

        assertThat(registered.getPosition()).isEqualTo(4);
        assertThat(registered.getCode()).isEqualTo("wifi");
        assertThat(registered.getValueType()).isEqualTo(CategoryAttributeType.BOOLEAN);
    }

    @Test
    void register_unknownCategory_answers404_beforeAnyWrite() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.register(unknown, "wifi", "Wi-Fi", "واي فاي",
                CategoryAttributeType.BOOLEAN))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void register_duplicateCode_failsLoudly() {
        when(repository.findByCategoryIdAndCode(categoryId, "wifi"))
                .thenReturn(Optional.of(saved(0)));

        assertThatThrownBy(() -> service.register(categoryId, "wifi", "Wi-Fi", "واي فاي",
                CategoryAttributeType.BOOLEAN))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void update_replacesTheDisplayFields_theIdentityPairUntouched() {
        CategoryAttribute existing = saved(2);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CategoryAttribute amended = service.update(existing.getId(), "Wireless", "لاسلكي",
                CategoryAttributeType.TEXT, 7);

        assertThat(amended.getLabelEn()).isEqualTo("Wireless");
        assertThat(amended.getLabelAr()).isEqualTo("لاسلكي");
        assertThat(amended.getValueType()).isEqualTo(CategoryAttributeType.TEXT);
        assertThat(amended.getPosition()).isEqualTo(7);
        assertThat(amended.getCode()).isEqualTo("wifi"); // identity immutable
        assertThat(amended.getCategoryId()).isEqualTo(categoryId);
    }

    @Test
    void remove_softDeletesThroughTheRepository() {
        CategoryAttribute existing = saved(0);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.remove(existing.getId());

        verify(repository).delete(existing);
    }

    @Test
    void reads_serveBothKeys_inPositionOrder() {
        List<CategoryAttribute> ordered = List.of(saved(0), saved(1));
        when(repository.findByCategoryIdOrderByPositionAsc(categoryId)).thenReturn(ordered);
        when(repository.findByCategoryCodeOrderByPositionAsc("stay")).thenReturn(ordered);

        assertThat(service.byCategoryId(categoryId)).isEqualTo(ordered);
        assertThat(service.byCategoryCode("stay")).isEqualTo(ordered);
    }
}
