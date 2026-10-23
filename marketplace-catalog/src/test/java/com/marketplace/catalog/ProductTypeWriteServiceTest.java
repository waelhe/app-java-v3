package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductTypeCreateCommand;
import com.marketplace.catalog.spi.ProductTypeMutationError;
import com.marketplace.catalog.spi.ProductTypeMutationErrorCode;
import com.marketplace.catalog.spi.ProductTypeMutationResult;
import com.marketplace.catalog.spi.ProductTypeUpdateCommand;
import com.marketplace.catalog.spi.ProductTypeWriteSpi;
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
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productTypeCreate}, {@code productTypeUpdate},
 * {@code productTypeDelete} and {@code productTypeBulkDelete} mutation
 * roots, the agent-2 packet): the owning write use case's unit proof —
 * the JUnit 5 + Mockito style the module's own
 * {@code CategoryWriteServiceTest} established (no Spring context: the
 * {@code @PreAuthorize} proxy is an integration concern proven by the
 * app module's real-stack tests; the business decisions the boundary
 * owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link ProductTypeWriteService} reachable through its typed seam —
 * the create's full validation ladder (blank/oversized name,
 * missing/invalid/duplicate slug, the happy path's row factory), the
 * update's partial-preserve semantics (the empty no-op, null fields
 * preserve, the blank-name INVALID, the immutable-slug refusals — a
 * different code, another row's code, an explicit blank — and the
 * row's-own-code idempotent re-send, the NOT_FOUND absence), the single
 * delete's NOT_FOUND and soft-delete happy path, and the bulk delete's
 * honest count (unknown ids skipped, never fabricated).</p>
 */
class ProductTypeWriteServiceTest {

    private ProductTypeRepository repository;
    private ProductTypeWriteSpi service;

    @BeforeEach
    void setUp() {
        repository = mock(ProductTypeRepository.class);
        service = new ProductTypeWriteService(repository);
    }

    private static ProductType row(String code, String nameEn) {
        return ProductType.create(code, nameEn, null, null);
    }

    // ------------------------------------------------------------------
    // createProductType — the validation ladder and the happy path
    // ------------------------------------------------------------------

    @Test
    void blankNameAnswersTheRequiredDomainError() {
        ProductTypeMutationResult result = service.createProductType(
                new ProductTypeCreateCommand("  ", "fresh-type"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsExactly(new ProductTypeMutationError(
                "name", ProductTypeMutationErrorCode.REQUIRED, "A product type name is required"));
        verify(repository, never()).save(any());
    }

    @Test
    void oversizedNameAnswersTheInvalidDomainError() {
        ProductTypeMutationResult result = service.createProductType(
                new ProductTypeCreateCommand("x".repeat(101), "fresh-type"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "name");
    }

    @Test
    void missingSlugAnswersTheRequiredDomainError() {
        ProductTypeMutationResult result = service.createProductType(
                new ProductTypeCreateCommand("Widget", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.REQUIRED, "slug");
    }

    @Test
    void invalidSlugShapeAnswersTheInvalidDomainError() {
        ProductTypeMutationResult result = service.createProductType(
                new ProductTypeCreateCommand("Widget", "Not_A_Slug"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateSlugAnswersTheAlreadyExistsDomainError() {
        when(repository.findByCode("taken")).thenReturn(Optional.of(row("taken", "Taken")));

        ProductTypeMutationResult result = service.createProductType(
                new ProductTypeCreateCommand("Widget", "taken"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.ALREADY_EXISTS, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void happyCreateSavesTheRowAndAnswersTheProjection() {
        when(repository.findByCode("fresh-type")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.createProductType(
                new ProductTypeCreateCommand("Fresh Widget", "fresh-type"));

        assertThat(result.success()).isTrue();
        assertThat(result.productType().name()).isEqualTo("Fresh Widget");
        assertThat(result.productType().slug()).isEqualTo("fresh-type");
        assertThat(result.productType().id()).isNotNull();
        verify(repository).save(any(ProductType.class));
    }

    // ------------------------------------------------------------------
    // updateProductType — the partial-preserve semantics
    // ------------------------------------------------------------------

    @Test
    void unknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        ProductTypeMutationResult result = service.updateProductType(unknown,
                new ProductTypeUpdateCommand("New", null));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void emptyCommandAnswersTheCurrentProjectionWithoutASave() {
        ProductType existing = row("keep-type", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ProductTypeMutationResult result = service.updateProductType(existing.getId(),
                new ProductTypeUpdateCommand(null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.productType().slug()).isEqualTo("keep-type");
        assertThat(result.productType().name()).isEqualTo("Kept");
        verify(repository, never()).save(any());
    }

    @Test
    void providedFieldsReplaceAndNullsPreserve() {
        ProductType existing = row("keep-type", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.updateProductType(existing.getId(),
                new ProductTypeUpdateCommand("Refreshed", null));

        assertThat(result.success()).isTrue();
        assertThat(result.productType().name()).isEqualTo("Refreshed");
        assertThat(result.productType().slug()).isEqualTo("keep-type");
        verify(repository).save(any(ProductType.class));
    }

    @Test
    void blankNameAnswersTheInvalidDomainError() {
        ProductType existing = row("keep-type", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ProductTypeMutationResult result = service.updateProductType(existing.getId(),
                new ProductTypeUpdateCommand("   ", null));

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "name");
        verify(repository, never()).save(any());
    }

    @Test
    void slugChangeToADifferentCodeAnswersTheImmutableDomainError() {
        // The entity's own law: the stable public code is IMMUTABLE —
        // a different slug (whether free or taken by another row) is
        // the classified INVALID refusal, never a silent drop and
        // never an unauthorized move.
        ProductType existing = row("keep-type", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("fresh-name")).thenReturn(Optional.empty());

        ProductTypeMutationResult result = service.updateProductType(existing.getId(),
                new ProductTypeUpdateCommand(null, "fresh-name"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "slug");
        assertThat(result.errors().get(0).message())
                .isEqualTo("A product type slug is immutable; it cannot change after creation");
        assertThat(existing.getCode()).isEqualTo("keep-type");
        verify(repository, never()).save(any());
    }

    @Test
    void slugChangeToAnotherRowsCodeAnswersTheImmutableDomainError() {
        // Even a slug owned by another row answers the immutable
        // refusal first — the code never moves, so the uniqueness
        // ladder below it is unreachable for a different code.
        ProductType existing = row("keep-type", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("taken")).thenReturn(Optional.of(row("taken", "Taken")));

        ProductTypeMutationResult result = service.updateProductType(existing.getId(),
                new ProductTypeUpdateCommand(null, "taken"));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "slug");
        verify(repository, never()).save(any());
    }

    @Test
    void slugChangeToTheRowsOwnCodeExecutes() {
        // The row's own code is EXCLUDED from the duplicate check — a
        // re-send of the current slug is a no-op, not a conflict.
        ProductType existing = row("keep-type", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(repository.findByCode("keep-type")).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductTypeMutationResult result = service.updateProductType(existing.getId(),
                new ProductTypeUpdateCommand("Refreshed", "keep-type"));

        assertThat(result.success()).isTrue();
        assertThat(result.productType().slug()).isEqualTo("keep-type");
        verify(repository).save(any(ProductType.class));
    }

    @Test
    void blankSlugOnUpdateAnswersTheImmutableDomainError() {
        // An explicitly blank slug is a change attempt (to blank) —
        // the immutable refusal answers it, never a silent drop.
        ProductType existing = row("keep-type", "Kept");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ProductTypeMutationResult result = service.updateProductType(existing.getId(),
                new ProductTypeUpdateCommand(null, "   "));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.INVALID, "slug");
        verify(repository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // deleteProductType / deleteProductTypes
    // ------------------------------------------------------------------

    @Test
    void deleteOfAnUnknownRowAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        ProductTypeMutationResult result = service.deleteProductType(unknown);

        assertThat(result.errors()).singleElement()
                .extracting(ProductTypeMutationError::code, ProductTypeMutationError::field)
                .containsExactly(ProductTypeMutationErrorCode.NOT_FOUND, "id");
        verify(repository, never()).delete(any(ProductType.class));
    }

    @Test
    void deleteAnswersTheLastKnownProjection() {
        ProductType existing = row("gone-type", "Gone");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        ProductTypeMutationResult result = service.deleteProductType(existing.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.productType().slug()).isEqualTo("gone-type");
        assertThat(result.productType().name()).isEqualTo("Gone");
        verify(repository).delete(existing);
    }

    @Test
    void bulkDeleteAnswersTheHonestCount() {
        ProductType first = row("first-type", "First");
        ProductType second = row("second-type", "Second");
        when(repository.findById(first.getId())).thenReturn(Optional.of(first));
        when(repository.findById(second.getId())).thenReturn(Optional.of(second));

        // Arrays.asList carries the null member the honest-skip case
        // feeds the owner (List.of's own null refusal is not the seam).
        int count = service.deleteProductTypes(java.util.Arrays.asList(
                first.getId(), second.getId(), UUID.randomUUID(), null));

        assertThat(count).isEqualTo(2);
        verify(repository).delete(first);
        verify(repository).delete(second);
    }

    @Test
    void bulkDeleteOfAnEmptyListAnswersZero() {
        assertThat(service.deleteProductTypes(List.of())).isZero();
        assertThat(service.deleteProductTypes(null)).isZero();
        verify(repository, never()).delete(any(ProductType.class));
    }

    // ------------------------------------------------------------------
    // the rendered projection
    // ------------------------------------------------------------------

    @Test
    void theProjectionRendersTheBilingualFallbackName() {
        // A nameAr-only row renders nameAr (the read service's own
        // fallback rule); a nameEn row renders nameEn.
        ProductType arOnly = ProductType.create("ar-type", null, "الاسم العربي فقط", null);
        when(repository.findById(arOnly.getId())).thenReturn(Optional.of(arOnly));

        ProductTypeMutationResult result = service.deleteProductType(arOnly.getId());

        assertThat(result.success()).isTrue();
        assertThat(result.productType().name()).isEqualTo("الاسم العربي فقط");
        assertThat(result.productType().slug()).isEqualTo("ar-type");
    }
}
