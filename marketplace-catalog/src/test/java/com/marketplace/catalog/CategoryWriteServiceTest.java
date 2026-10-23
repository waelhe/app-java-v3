package com.marketplace.catalog;

import com.marketplace.catalog.spi.CategoryCreateCommand;
import com.marketplace.catalog.spi.CategoryMutationError;
import com.marketplace.catalog.spi.CategoryMutationErrorCode;
import com.marketplace.catalog.spi.CategoryMutationResult;
import com.marketplace.catalog.spi.CategoryUpdateCommand;
import com.marketplace.catalog.spi.CategoryView;
import com.marketplace.catalog.spi.CategoryWriteSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categoryCreate},
 * {@code categoryUpdate}, {@code categoryDelete} and
 * {@code categoryBulkDelete} mutation roots): the owning write use
 * case's unit proof — the JUnit 5 + Mockito style the module's own
 * {@code CategoryReadServiceTest} established (no Spring context: the
 * {@code @PreAuthorize} proxy is an integration concern proven by the
 * app module's real-stack tests; the business decisions the boundary
 * owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link CategoryWriteService} reachable through its typed seam — the
 * create's full validation ladder (blank/oversized name, missing/invalid/
 * duplicate slug, the happy path's row factory and position assignment),
 * the update's partial-preserve semantics (null fields preserve, the
 * blank-name INVALID, the duplicate-code negative EXCLUDING the row's
 * own id, the NOT_FOUND absence), the single delete's NOT_FOUND and
 * soft-delete happy path, and the bulk delete's honest count (unknown
 * ids skipped, never fabricated).</p>
 */
class CategoryWriteServiceTest {

    private CategoryRepository repository;
    private CategoryWriteSpi service;

    @BeforeEach
    void setUp() {
        repository = mock(CategoryRepository.class);
        service = new CategoryWriteService(repository);
    }

    private static Category row(String code, String nameEn, int position) {
        return Category.create(code, nameEn, null, position);
    }

    // ------------------------------------------------------------------
    // createCategory — the validation ladder and the happy path
    // ------------------------------------------------------------------

    @Test
    void blankNameAnswersTheRequiredDomainError() {
        CategoryMutationResult result = service.createCategory(new CategoryCreateCommand("  ", "fresh-code"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsExactly(new CategoryMutationError(
                "name", CategoryMutationErrorCode.REQUIRED, "A category name is required"));
        verify(repository, never()).save(any());
    }

    @Test
    void oversizedNameAnswersTheInvalidDomainError() {
        CategoryMutationResult result = service.createCategory(
                new CategoryCreateCommand("x".repeat(101), "fresh-code"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.INVALID, "name");
    }

    @Test
    void missingSlugAnswersTheRequiredDomainError() {
        CategoryMutationResult result = service.createCategory(new CategoryCreateCommand("Fresh", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.REQUIRED, "slug");
    }

    @Test
    void invalidSlugShapeAnswersTheInvalidDomainError() {
        CategoryMutationResult result = service.createCategory(new CategoryCreateCommand("Fresh", "Not_A-Code"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.INVALID, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateActiveSlugAnswersTheAlreadyExistsDomainError() {
        when(repository.findByCode("taken")).thenReturn(Optional.of(row("taken", "Taken", 1)));

        CategoryMutationResult result = service.createCategory(new CategoryCreateCommand("Fresh", "taken"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.ALREADY_EXISTS, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void happyCreateSavesTheRowWithTheNextPositionAndAnswersTheProjection() {
        when(repository.findByCode("fresh-code")).thenReturn(Optional.empty());
        when(repository.findAllByOrderByPositionAsc()).thenReturn(List.of(row("a", "A", 1), row("b", "B", 2)));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryMutationResult result = service.createCategory(
                new CategoryCreateCommand("Fresh Code", "fresh-code"));

        assertThat(result.success()).isTrue();
        assertThat(result.category()).isNotNull();
        assertThat(result.category().slug()).isEqualTo("fresh-code");
        assertThat(result.category().name()).isEqualTo("Fresh Code");
        assertThat(result.category().position()).isEqualTo(3);
        verify(repository).save(any());
    }

    @Test
    void createNormalizesTheSlugToTheRegistrysOwnLowercaseContract() {
        when(repository.findByCode("mixed-case")).thenReturn(Optional.empty());
        when(repository.findAllByOrderByPositionAsc()).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryMutationResult result = service.createCategory(
                new CategoryCreateCommand("Fresh", "Mixed-Case"));

        assertThat(result.success()).isTrue();
        assertThat(result.category().slug()).isEqualTo("mixed-case");
    }

    // ------------------------------------------------------------------
    // updateCategory — the partial-preserve semantics
    // ------------------------------------------------------------------

    @Test
    void unknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        CategoryMutationResult result = service.updateCategory(unknown,
                new CategoryUpdateCommand("Fresh", "fresh"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void nullFieldsPreserveEveryColumn() {
        Category existing = row("keep-code", "Keep", 4);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryMutationResult result = service.updateCategory(existing.getId(),
                new CategoryUpdateCommand(null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.category().slug()).isEqualTo("keep-code");
        assertThat(result.category().name()).isEqualTo("Keep");
        verify(repository).save(existing);
    }

    @Test
    void providedFieldsReplaceTheColumns() {
        Category existing = row("old-code", "Old", 4);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("new-code")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryMutationResult result = service.updateCategory(existing.getId(),
                new CategoryUpdateCommand("New", "NEW-CODE"));

        assertThat(result.success()).isTrue();
        assertThat(result.category().slug()).isEqualTo("new-code");
        assertThat(result.category().name()).isEqualTo("New");
    }

    @Test
    void blankNameOnUpdateAnswersTheInvalidDomainError() {
        Category existing = row("code", "Old", 4);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        CategoryMutationResult result = service.updateCategory(existing.getId(),
                new CategoryUpdateCommand(" ", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.INVALID, "name");
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateSlugExcludingTheRowsOwnIdAnswersAlreadyExists() {
        Category existing = row("own-code", "Old", 4);
        Category other = row("other-code", "Other", 5);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("other-code")).thenReturn(Optional.of(other));

        CategoryMutationResult result = service.updateCategory(existing.getId(),
                new CategoryUpdateCommand(null, "other-code"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code, CategoryMutationError::field)
                .containsExactly(CategoryMutationErrorCode.ALREADY_EXISTS, "slug");
    }

    @Test
    void keepingTheRowsOwnSlugIsNotADuplicate() {
        Category existing = row("own-code", "Old", 4);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("own-code")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CategoryMutationResult result = service.updateCategory(existing.getId(),
                new CategoryUpdateCommand("Refreshed", "own-code"));

        assertThat(result.success()).isTrue();
        assertThat(result.category().name()).isEqualTo("Refreshed");
    }

    // ------------------------------------------------------------------
    // deleteCategory / deleteCategories — the soft-delete discipline
    // ------------------------------------------------------------------

    @Test
    void deleteAnswersTheLastKnownProjectionAndSoftDeletesTheRow() {
        Category existing = row("gone", "Gone", 7);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        CategoryMutationResult result = service.deleteCategory(existing.getId());

        assertThat(result.success()).isTrue();
        CategoryView lastKnown = result.category();
        assertThat(lastKnown.slug()).isEqualTo("gone");
        assertThat(lastKnown.name()).isEqualTo("Gone");
        verify(repository).delete(existing);
    }

    @Test
    void deleteOfAnUnknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        CategoryMutationResult result = service.deleteCategory(unknown);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CategoryMutationError::code)
                .isEqualTo(CategoryMutationErrorCode.NOT_FOUND);
        verify(repository, never()).delete(any());
    }

    @Test
    void bulkDeleteCountsOnlyTheRowsTheRegistryItselfDeleted() {
        Category first = row("one", "One", 1);
        Category second = row("two", "Two", 2);
        UUID unknown = UUID.randomUUID();
        when(repository.findById(first.getId())).thenReturn(Optional.of(first));
        when(repository.findById(second.getId())).thenReturn(Optional.of(second));
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        int count = service.deleteCategories(List.of(first.getId(), unknown, second.getId()));

        assertThat(count).isEqualTo(2);
        verify(repository).delete(first);
        verify(repository).delete(second);
    }

    @Test
    void bulkDeleteOfAnEmptyListAnswersZeroWithoutTouchingTheRegistry() {
        assertThat(service.deleteCategories(List.of())).isZero();
        assertThat(service.deleteCategories(null)).isZero();
        verify(repository, never()).delete(any());
    }
}
