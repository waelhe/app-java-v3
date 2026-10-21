package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductCreateCommand;
import com.marketplace.catalog.spi.ProductMutationError;
import com.marketplace.catalog.spi.ProductMutationErrorCode;
import com.marketplace.catalog.spi.ProductMutationResult;
import com.marketplace.catalog.spi.ProductUpdateCommand;
import com.marketplace.catalog.spi.ProductWriteSpi;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productCreate}, {@code productUpdate}, {@code productDelete}
 * and {@code productBulkDelete} mutation roots, the agent-2 packet):
 * the owning write use case's unit proof — the JUnit 5 + Mockito style
 * the module's own {@code CategoryWriteServiceTest} established (no
 * Spring context: the {@code @PreAuthorize} proxy is an integration
 * concern proven by the app module's real-stack tests; the business
 * decisions the boundary owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link ProductWriteService} reachable through its typed seam — the
 * create's full validation ladder (blank/oversized name, oversized
 * description, missing/negative price, malformed currency, missing and
 * unknown store-category code, unknown product type, the happy path's
 * row factory with the caller as the owning provider), the update's
 * partial-preserve semantics (the empty no-op, null fields preserve,
 * the merged-name/description/price re-checks, the ARCHIVED terminal
 * refusal, the stranger-row safe absence), the single delete's safe
 * absence and soft-delete happy path, and the bulk delete's honest
 * count (stranger ids skipped, never fabricated).</p>
 */
class ProductWriteServiceTest {

    private ProductRepository productRepository;
    private StoreCategoryRepository storeCategoryRepository;
    private ProductTypeRepository productTypeRepository;
    private CurrentUserProvider currentUserProvider;
    private ProductWriteSpi service;
    private final Authentication caller = new TestingAuthenticationToken("provider-subject", "n/a", "ROLE_PROVIDER");

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        storeCategoryRepository = mock(StoreCategoryRepository.class);
        productTypeRepository = mock(ProductTypeRepository.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        service = new ProductWriteService(productRepository, storeCategoryRepository,
                productTypeRepository, currentUserProvider);
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(ProviderIds.CALLER);
        // The reference dictionary row is present by default so each
        // ladder test isolates ITS own field's error (the unknown-code
        // negative stubs its own absence below).
        when(storeCategoryRepository.findByCode("widgets"))
                .thenReturn(Optional.of(mock(StoreCategory.class)));
    }

    /** The caller's stable provider id — the owning provider fact. */
    private static final class ProviderIds {
        static final UUID CALLER = UUID.fromString("00000000-0000-0000-0000-000000000001");
        static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    }

    private static Product rowOwnedBy(UUID providerId) {
        return Product.register("widgets", "Owned Widget", "fixture", 1000L, "SAR", providerId);
    }

    private static ProductCreateCommand create(String name, String description, String storeCategoryCode,
                                                Long priceMinor, String currency, UUID productTypeId) {
        return new ProductCreateCommand(name, description, storeCategoryCode, priceMinor, currency, productTypeId);
    }

    // ------------------------------------------------------------------
    // createProduct — the validation ladder and the happy path
    // ------------------------------------------------------------------

    @Test
    void blankNameAnswersTheRequiredDomainError() {
        ProductMutationResult result = service.createProduct(
                create("  ", null, "widgets", 1000L, "SAR", null), caller);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsExactly(new ProductMutationError(
                "name", ProductMutationErrorCode.REQUIRED, "A product name is required"));
        verify(productRepository, never()).save(any());
    }

    @Test
    void oversizedNameAnswersTheInvalidDomainError() {
        ProductMutationResult result = service.createProduct(
                create("x".repeat(201), null, "widgets", 1000L, "SAR", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, "name");
    }

    @Test
    void oversizedDescriptionAnswersTheInvalidDomainError() {
        ProductMutationResult result = service.createProduct(
                create("Widget", "d".repeat(2001), "widgets", 1000L, "SAR", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, "description");
    }

    @Test
    void missingPriceAnswersTheRequiredDomainError() {
        ProductMutationResult result = service.createProduct(
                create("Widget", null, "widgets", null, "SAR", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.REQUIRED, "priceMinor");
    }

    @Test
    void negativePriceAnswersTheInvalidDomainError() {
        ProductMutationResult result = service.createProduct(
                create("Widget", null, "widgets", -1L, "SAR", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, "priceMinor");
    }

    @Test
    void malformedCurrencyAnswersTheRequiredDomainError() {
        ProductMutationResult result = service.createProduct(
                create("Widget", null, "widgets", 1000L, "riyals", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.REQUIRED, "currency");
    }

    @Test
    void missingStoreCategoryCodeAnswersTheRequiredDomainError() {
        ProductMutationResult result = service.createProduct(
                create("Widget", null, null, 1000L, "SAR", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.REQUIRED, "storeCategoryCode");
    }

    @Test
    void unknownStoreCategoryCodeAnswersTheNotFoundDomainError() {
        when(storeCategoryRepository.findByCode("missing")).thenReturn(Optional.empty());

        ProductMutationResult result = service.createProduct(
                create("Widget", null, "missing", 1000L, "SAR", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.NOT_FOUND, "storeCategoryCode");
        verify(productRepository, never()).save(any());
    }

    @Test
    void unknownProductTypeAnswersTheNotFoundDomainError() {
        when(storeCategoryRepository.findByCode("widgets")).thenReturn(Optional.of(mock(StoreCategory.class)));
        UUID unknownType = UUID.randomUUID();
        when(productTypeRepository.findById(unknownType)).thenReturn(Optional.empty());

        ProductMutationResult result = service.createProduct(
                create("Widget", null, "widgets", 1000L, "SAR", unknownType), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.NOT_FOUND, "productType");
        verify(productRepository, never()).save(any());
    }

    @Test
    void happyCreateSavesTheCallersRowAndAnswersTheProjection() {
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductMutationResult result = service.createProduct(
                create("Fresh Widget", "Fresh", "widgets", 1250L, "sar", null), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.product().name()).isEqualTo("Fresh Widget");
        assertThat(result.product().status()).isEqualTo("ACTIVE");
        assertThat(result.product().id()).isNotNull();
        verify(productRepository).save(argThat((Product saved) ->
                saved.getProviderId().equals(ProviderIds.CALLER)
                        && "SAR".equals(saved.getCurrency())
                        && saved.getPriceMinor() == 1250L
                        && "widgets".equals(saved.getStoreCategoryCode())));
    }

    @Test
    void happyCreateWithATypedProductStartsAsDraft() {
        UUID typeId = UUID.randomUUID();
        when(productTypeRepository.findById(typeId)).thenReturn(Optional.of(mock(ProductType.class)));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductMutationResult result = service.createProduct(
                create("Typed Widget", null, "widgets", 1250L, "SAR", typeId), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.product().status()).isEqualTo("DRAFT");
        assertThat(result.product().productTypeId()).isEqualTo(typeId);
    }

    // ------------------------------------------------------------------
    // updateProduct — the partial-preserve semantics and the ownership law
    // ------------------------------------------------------------------

    @Test
    void unknownIdAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(productRepository.findById(unknown)).thenReturn(Optional.empty());

        ProductMutationResult result = service.updateProduct(unknown,
                new ProductUpdateCommand("New", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void aStrangersRowAnswersTheSameSafeAbsence() {
        Product strangers = rowOwnedBy(ProviderIds.STRANGER);
        when(productRepository.findById(strangers.getId())).thenReturn(Optional.of(strangers));

        ProductMutationResult result = service.updateProduct(strangers.getId(),
                new ProductUpdateCommand("New", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.NOT_FOUND, "id");
        verify(productRepository, never()).save(any());
    }

    @Test
    void emptyCommandAnswersTheCurrentProjectionWithoutASave() {
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(owned.getId())).thenReturn(Optional.of(owned));

        ProductMutationResult result = service.updateProduct(owned.getId(),
                new ProductUpdateCommand(null, null, null), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.product().name()).isEqualTo("Owned Widget");
        verify(productRepository, never()).save(any());
    }

    @Test
    void providedFieldsReplaceAndNullsPreserve() {
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(owned.getId())).thenReturn(Optional.of(owned));
        when(productRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProductMutationResult result = service.updateProduct(owned.getId(),
                new ProductUpdateCommand("Refreshed", null, 2000L), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.product().name()).isEqualTo("Refreshed");
        assertThat(result.product().id()).isEqualTo(owned.getId());
        verify(productRepository).save(argThat((Product saved) ->
                saved.getDescription().equals("fixture") && saved.getPriceMinor() == 2000L));
    }

    @Test
    void blankNameAnswersTheInvalidDomainError() {
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(owned.getId())).thenReturn(Optional.of(owned));

        ProductMutationResult result = service.updateProduct(owned.getId(),
                new ProductUpdateCommand("   ", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, "name");
        verify(productRepository, never()).save(any());
    }

    @Test
    void negativeMergedPriceAnswersTheInvalidDomainError() {
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(owned.getId())).thenReturn(Optional.of(owned));

        ProductMutationResult result = service.updateProduct(owned.getId(),
                new ProductUpdateCommand(null, null, -5L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, "priceMinor");
        verify(productRepository, never()).save(any());
    }

    @Test
    void anArchivedRowIsTerminalAndAnswersTheInvalidDomainError() {
        Product archived = rowOwnedBy(ProviderIds.CALLER);
        archived.archive();
        when(productRepository.findById(archived.getId())).thenReturn(Optional.of(archived));

        ProductMutationResult result = service.updateProduct(archived.getId(),
                new ProductUpdateCommand("New", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, null);
        verify(productRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // deleteProduct / deleteProducts
    // ------------------------------------------------------------------

    @Test
    void deleteOfAStrangersRowAnswersTheSafeAbsence() {
        Product strangers = rowOwnedBy(ProviderIds.STRANGER);
        when(productRepository.findById(strangers.getId())).thenReturn(Optional.of(strangers));

        ProductMutationResult result = service.deleteProduct(strangers.getId(), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code)
                .isEqualTo(ProductMutationErrorCode.NOT_FOUND);
        verify(productRepository, never()).delete(any(Product.class));
    }

    @Test
    void deleteAnswersTheLastKnownProjection() {
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(owned.getId())).thenReturn(Optional.of(owned));

        ProductMutationResult result = service.deleteProduct(owned.getId(), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.product().name()).isEqualTo("Owned Widget");
        verify(productRepository).delete(owned);
    }

    @Test
    void bulkDeleteAnswersTheHonestOwnedCount() {
        Product first = rowOwnedBy(ProviderIds.CALLER);
        Product strangers = rowOwnedBy(ProviderIds.STRANGER);
        when(productRepository.findById(first.getId())).thenReturn(Optional.of(first));
        when(productRepository.findById(strangers.getId())).thenReturn(Optional.of(strangers));

        // Arrays.asList carries the null member the honest-skip case
        // feeds the owner (List.of's own null refusal is not the seam).
        int count = service.deleteProducts(java.util.Arrays.asList(
                first.getId(), strangers.getId(), UUID.randomUUID(), null), caller);

        assertThat(count).isEqualTo(1);
        verify(productRepository).delete(first);
        verify(productRepository, never()).delete(strangers);
    }

    @Test
    void bulkDeleteOfAnEmptyListAnswersZero() {
        assertThat(service.deleteProducts(List.of(), caller)).isZero();
        assertThat(service.deleteProducts(null, caller)).isZero();
        verify(productRepository, never()).delete(any(Product.class));
    }
}
