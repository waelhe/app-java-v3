package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductVariantBulkUpdateItem;
import com.marketplace.catalog.spi.ProductVariantCreateCommand;
import com.marketplace.catalog.spi.ProductVariantMutationError;
import com.marketplace.catalog.spi.ProductVariantMutationErrorCode;
import com.marketplace.catalog.spi.ProductVariantMutationResult;
import com.marketplace.catalog.spi.ProductVariantUpdateCommand;
import com.marketplace.catalog.spi.ProductVariantWriteSpi;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantCreate}, {@code productVariantUpdate},
 * {@code productVariantDelete} and {@code productVariantBulkDelete}
 * mutation roots, the agent-2 packet): the owning write use case's
 * unit proof — the JUnit 5 + Mockito style the module's own
 * {@code ProductWriteServiceTest} established (no Spring context: the
 * {@code @PreAuthorize} proxy is an integration concern proven by the
 * app module's real-stack tests; the business decisions the boundary
 * owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link ProductVariantWriteService} reachable through its typed seam
 * — the create's full validation ladder (missing/unknown/stranger
 * product, blank/oversized/duplicate SKU, oversized name,
 * missing/negative price, the happy path's row factory with the
 * provider and currency copied from the parent), the update's
 * partial-preserve semantics (the empty no-op, null fields preserve,
 * the merged SKU namespace re-check with the same-sku idempotent
 * re-send, the ARCHIVED terminal refusal, the stranger-row safe
 * absence), the single delete's safe absence and soft-delete happy
 * path, and the bulk delete's honest count (stranger ids skipped,
 * never fabricated).</p>
 */
class ProductVariantWriteServiceTest {

    private ProductVariantRepository variantRepository;
    private ProductRepository productRepository;
    private CurrentUserProvider currentUserProvider;
    private ProductTypeRepository productTypeRepository;
    private ProductTypeAttributeAssignmentRepository assignmentRepository;
    private ProductVariantWriteSpi service;
    private final Authentication caller = new TestingAuthenticationToken("provider-subject", "n/a", "ROLE_PROVIDER");

    @BeforeEach
    void setUp() {
        variantRepository = mock(ProductVariantRepository.class);
        productRepository = mock(ProductRepository.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        productTypeRepository = mock(ProductTypeRepository.class);
        assignmentRepository = mock(ProductTypeAttributeAssignmentRepository.class);
        service = new ProductVariantWriteService(variantRepository, productRepository,
                currentUserProvider, productTypeRepository, assignmentRepository);
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(ProviderIds.CALLER);
    }

    /** The caller's stable provider id — the owning provider fact. */
    private static final class ProviderIds {
        static final UUID CALLER = UUID.fromString("00000000-0000-0000-0000-000000000001");
        static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    }

    private static Product productOwnedBy(UUID providerId) {
        return Product.register("widgets", "Owned Widget", "fixture", 1000L, "SAR", providerId, null);
    }

    private static ProductVariant variantUnder(Product parent, String sku) {
        return ProductVariant.register(parent, sku, "Large / Red", 149900L);
    }

    private static ProductVariantCreateCommand create(UUID productId, String sku, String name, Long priceMinor) {
        return new ProductVariantCreateCommand(productId, sku, name, priceMinor);
    }

    /** A typed product — the register seam's own shape (a non-null productTypeId). */
    private static Product typedProductOwnedBy(UUID providerId, UUID productTypeId) {
        return Product.register("widgets", "Owned Widget", "fixture", 1000L, "SAR", providerId, productTypeId);
    }

    private static ProductTypeAttributeAssignment assignmentOf(UUID productTypeId,
                                                                ProductTypeAttributeScope scope, boolean required) {
        return ProductTypeAttributeAssignment.create(productTypeId, UUID.randomUUID(), scope, required, 0);
    }

    // ------------------------------------------------------------------
    // createVariant — the validation ladder and the happy path
    // ------------------------------------------------------------------

    @Test
    void missingProductAnswersTheRequiredDomainError() {
        ProductVariantMutationResult result = service.createVariant(
                create(null, "SKU-1", "Large", 1000L), caller);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "product");
        verify(variantRepository, never()).save(any());
    }

    @Test
    void unknownProductAnswersTheRequiredDomainError() {
        UUID unknown = UUID.randomUUID();
        when(productRepository.findById(unknown)).thenReturn(Optional.empty());

        ProductVariantMutationResult result = service.createVariant(
                create(unknown, "SKU-1", "Large", 1000L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "product");
    }

    @Test
    void aStrangersProductAnswersTheSameSafeAbsenceNeverAnExistenceDisclosure() {
        Product strangerProduct = productOwnedBy(ProviderIds.STRANGER);
        when(productRepository.findById(strangerProduct.getId()))
                .thenReturn(Optional.of(strangerProduct));

        ProductVariantMutationResult result = service.createVariant(
                create(strangerProduct.getId(), "SKU-1", "Large", 1000L), caller);

        // The ownership law's own safe absence: the stranger's product
        // answers the SAME REQUIRED refusal shape as a missing product.
        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "product");
        verify(variantRepository, never()).save(any());
    }

    /**
     * Row 12 (agent findings 2026-10-26 14:33 export, CWE-203): the foreign
     * parent ends the evaluation — the SKU uniqueness check (which reads
     * the STRANGER's namespace) is never reached, so its ALREADY_EXISTS
     * error cannot disclose whether a guessed SKU exists in the foreign
     * seller's namespace. The refusal carries the product error alone,
     * and the seller's own namespace query never runs.
     */
    @Test
    void aStrangersProductSkipsTheSkuNamespaceQueryEntirely() {
        Product strangerProduct = productOwnedBy(ProviderIds.STRANGER);
        when(productRepository.findById(strangerProduct.getId()))
                .thenReturn(Optional.of(strangerProduct));

        ProductVariantMutationResult result = service.createVariant(
                create(strangerProduct.getId(), "SKU-9", "Large", 1000L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "product");
        verify(variantRepository, never())
                .countSkuReservationsIncludingDeleted(any(), anyString());
        verify(variantRepository, never()).save(any());
    }

    @Test
    void blankSkuAnswersTheRequiredDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "  ", "Large", 1000L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "sku");
    }

    @Test
    void oversizedSkuAnswersTheInvalidDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "S".repeat(101), "Large", 1000L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "sku");
    }

    @Test
    void duplicateSkuAnswersTheAlreadyExistsDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        // The 2026-11-06 #808 round: the reservation probe spans the RAW
        // table — a live OR soft-deleted row's SKU answers the same
        // classified ALREADY_EXISTS (the native count, never the
        // soft-delete-filtered derived lookup).
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-1"))
                .thenReturn(1L);

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large", 1000L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.ALREADY_EXISTS, "sku");
        verify(variantRepository, never()).save(any());
    }

    @Test
    void aSoftDeletedRowsSkuStaysReservedOnCreateToo() {
        // The 2026-11-06 #808 round (Minor · Data Integrity): V219's
        // unique index is GLOBAL — a soft-deleted variant keeps its SKU
        // reserved, so the raw-table count answers the classified
        // ALREADY_EXISTS where the live-rows-only probe saw nothing.
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-1"))
                .thenReturn(1L);

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large", 1000L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.ALREADY_EXISTS, "sku");
        verify(variantRepository, never()).save(any());
    }

    @Test
    void oversizedNameAnswersTheInvalidDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "L".repeat(201), 1000L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "name");
    }

    @Test
    void missingPriceAnswersTheRequiredDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "priceMinor");
    }

    @Test
    void negativePriceAnswersTheInvalidDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large", -1L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "priceMinor");
    }

    @Test
    void theHappyPathRegistersTheRowWithTheOwnershipFactsCopiedFromTheParent() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-1"))
                .thenReturn(0L);
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large / Red", 149900L), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.variant().sku()).isEqualTo("SKU-1");
        assertThat(result.variant().name()).isEqualTo("Large / Red");
        assertThat(result.variant().status()).isEqualTo("ACTIVE");
        assertThat(result.variant().product().id()).isEqualTo(parent.getId());
        // The ADR's decision 3: the provider and the currency are the
        // service's own copy from the owning product, and the stock
        // starts at zero (never client-supplied).
        verify(variantRepository).save(argThat((ProductVariant row) ->
                row.getProviderId().equals(ProviderIds.CALLER)
                        && row.getProductId().equals(parent.getId())
                        && "SAR".equals(row.getCurrency())
                        && row.getStockQuantity() == 0));
    }

    // ------------------------------------------------------------------
    // createVariant — the typed-schema eligibility gate (the 2026-11-06
    // #808 round-3 review, Major · Data Integrity & Integration)
    // ------------------------------------------------------------------

    @Test
    void aSelectionSchemaProductRefusesTheValuelessCreateWithTheInvalidDomainError() {
        UUID productTypeId = UUID.randomUUID();
        Product parent = typedProductOwnedBy(ProviderIds.CALLER, productTypeId);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-1"))
                .thenReturn(0L);
        when(productTypeRepository.findByIdForShare(productTypeId))
                .thenReturn(Optional.of(ProductType.create("widgets", "Widgets", null, null)));
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId))
                .thenReturn(List.of(assignmentOf(productTypeId,
                        ProductTypeAttributeScope.VARIANT_SELECTION, true)));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large / Red", 149900L), caller);

        // The command carries NO attribute values: an ACTIVE row with a
        // null combination key would bypass the partial unique index and
        // strand the publication gate — the typed registration path owns
        // those variants.
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "product");
        verify(variantRepository, never()).save(any(ProductVariant.class));
    }

    @Test
    void aRequiredVariantAssignmentRefusesTheValuelessCreateToo() {
        UUID productTypeId = UUID.randomUUID();
        Product parent = typedProductOwnedBy(ProviderIds.CALLER, productTypeId);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-1"))
                .thenReturn(0L);
        when(productTypeRepository.findByIdForShare(productTypeId))
                .thenReturn(Optional.of(ProductType.create("widgets", "Widgets", null, null)));
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId))
                .thenReturn(List.of(assignmentOf(productTypeId,
                        ProductTypeAttributeScope.VARIANT, true)));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large / Red", 149900L), caller);

        // The registration path's own zero-values eligibility law: a
        // required VARIANT assignment demands its value as well.
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "product");
        verify(variantRepository, never()).save(any(ProductVariant.class));
    }

    @Test
    void aTypedProductWithoutValueDemandsKeepsTheFreeFormCreatePath() {
        UUID productTypeId = UUID.randomUUID();
        Product parent = typedProductOwnedBy(ProviderIds.CALLER, productTypeId);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-1"))
                .thenReturn(0L);
        when(productTypeRepository.findByIdForShare(productTypeId))
                .thenReturn(Optional.of(ProductType.create("widgets", "Widgets", null, null)));
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId))
                .thenReturn(List.of(assignmentOf(productTypeId,
                        ProductTypeAttributeScope.VARIANT, false)));
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large / Red", 149900L), caller);

        // The free-form path survives for the products whose schema
        // demands nothing (the finding's own directive).
        assertThat(result.success()).isTrue();
        verify(variantRepository).save(any(ProductVariant.class));
    }

    @Test
    void aWithdrawnTypeSchemaKeepsTheFreeFormCreatePath() {
        UUID productTypeId = UUID.randomUUID();
        Product parent = typedProductOwnedBy(ProviderIds.CALLER, productTypeId);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-1"))
                .thenReturn(0L);
        // A soft-deleted type row answers the share read empty — the
        // schema is gone, the free-form path is the honest answer.
        when(productTypeRepository.findByIdForShare(productTypeId)).thenReturn(Optional.empty());
        when(assignmentRepository.findAllByProductTypeIdOrderByScopeAscPositionAsc(productTypeId))
                .thenReturn(List.of());
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProductVariantMutationResult result = service.createVariant(
                create(parent.getId(), "SKU-1", "Large / Red", 149900L), caller);

        assertThat(result.success()).isTrue();
        verify(variantRepository).save(any(ProductVariant.class));
    }

    // ------------------------------------------------------------------
    // updateVariant — the partial-preserve semantics and the ladder
    // ------------------------------------------------------------------

    @Test
    void anUnknownRowAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(variantRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        ProductVariantMutationResult result = service.updateVariant(
                unknown, new ProductVariantUpdateCommand("SKU-2", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void aStrangersRowAnswersTheSameSafeAbsenceNeverAnExistenceDisclosure() {
        Product strangerProduct = productOwnedBy(ProviderIds.STRANGER);
        ProductVariant strangerRow = variantUnder(strangerProduct, "SKU-9");
        when(variantRepository.findByIdForUpdate(strangerRow.getId())).thenReturn(Optional.of(strangerRow));

        ProductVariantMutationResult result = service.updateVariant(
                strangerRow.getId(), new ProductVariantUpdateCommand("SKU-2", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void theEmptyCommandAnswersTheCurrentProjectionWithoutAWrite() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.updateVariant(
                row.getId(), new ProductVariantUpdateCommand(null, null, null), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.variant().sku()).isEqualTo("SKU-1");
        verify(variantRepository, never()).save(any());
    }

    @Test
    void anArchivedRowIsTerminalAndAnswersTheInvalidDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant archived = variantUnder(parent, "SKU-1");
        archived.archive();
        when(variantRepository.findByIdForUpdate(archived.getId())).thenReturn(Optional.of(archived));

        ProductVariantMutationResult result = service.updateVariant(
                archived.getId(), new ProductVariantUpdateCommand("SKU-2", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, null);
        verify(variantRepository, never()).save(any());
    }

    @Test
    void aBlankSkuOnUpdateAnswersTheInvalidDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));

        ProductVariantMutationResult result = service.updateVariant(
                row.getId(), new ProductVariantUpdateCommand("  ", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "sku");
    }

    @Test
    void anotherRowsSkuAnswersTheAlreadyExistsDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        ProductVariant other = variantUnder(parent, "SKU-2");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(variantRepository.countSkuReservationsIncludingDeletedExcluding(
                ProviderIds.CALLER, "SKU-2", row.getId())).thenReturn(1L);

        ProductVariantMutationResult result = service.updateVariant(
                row.getId(), new ProductVariantUpdateCommand("SKU-2", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.ALREADY_EXISTS, "sku");
        verify(variantRepository, never()).save(any());
    }

    @Test
    void aSoftDeletedRowsSkuStaysReservedOnUpdateToo() {
        // The 2026-11-06 #808 round (Minor · Data Integrity), the update
        // leg: the reservation count spans the RAW table and excludes the
        // addressed row itself — a soft-deleted sibling's SKU answers the
        // classified ALREADY_EXISTS, never the raw constraint error.
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(variantRepository.countSkuReservationsIncludingDeletedExcluding(
                ProviderIds.CALLER, "SKU-2", row.getId())).thenReturn(1L);

        ProductVariantMutationResult result = service.updateVariant(
                row.getId(), new ProductVariantUpdateCommand("SKU-2", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.ALREADY_EXISTS, "sku");
        verify(variantRepository, never()).save(any());
    }

    @Test
    void theSameSkuResendStaysTheIdempotentNoOp() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        // The same-sku re-send excludes the addressed row itself — the
        // raw-table reservation count of the OTHER rows is what the
        // idempotent no-op rides (the 2026-11-06 #808 round).
        when(variantRepository.countSkuReservationsIncludingDeletedExcluding(
                ProviderIds.CALLER, "SKU-1", row.getId())).thenReturn(0L);
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.updateVariant(
                row.getId(), new ProductVariantUpdateCommand("SKU-1", null, null), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.variant().sku()).isEqualTo("SKU-1");
    }

    @Test
    void aNegativePriceOnUpdateAnswersTheInvalidDomainError() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));

        ProductVariantMutationResult result = service.updateVariant(
                row.getId(), new ProductVariantUpdateCommand(null, null, -5L), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "priceMinor");
    }

    @Test
    void theHappyUpdateAmendsTheMovableSetAndPreservesTheOwnershipFacts() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(variantRepository.countSkuReservationsIncludingDeletedExcluding(
                ProviderIds.CALLER, "SKU-2", row.getId())).thenReturn(0L);
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.updateVariant(
                row.getId(), new ProductVariantUpdateCommand("SKU-2", "Small / Blue", 99900L), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.variant().sku()).isEqualTo("SKU-2");
        assertThat(result.variant().name()).isEqualTo("Small / Blue");
        verify(variantRepository).save(argThat((ProductVariant amended) ->
                amended.getProviderId().equals(ProviderIds.CALLER)
                        && amended.getProductId().equals(parent.getId())
                        && amended.getPriceMinor() == 99900L
                        && amended.getStockQuantity() == 0));
    }

    // ------------------------------------------------------------------
    // deleteVariant / deleteVariants — the safe absence and honest count
    // ------------------------------------------------------------------

    @Test
    void deletingAnUnknownRowAnswersTheNotFoundDomainError() {
        UUID unknown = UUID.randomUUID();
        when(variantRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        ProductVariantMutationResult result = service.deleteVariant(unknown, caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void theHappyDeleteSoftDeletesAndAnswersTheLastKnownProjection() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        ProductVariantMutationResult result = service.deleteVariant(row.getId(), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.variant().sku()).isEqualTo("SKU-1");
        verify(variantRepository).delete(row);
    }

    /**
     * The 2026-11-05 #808 round (Major · Functional Correctness): the
     * variant shelf's own B7 wall — a variant holding reserved units is
     * the paid order's stock and can never be released or fulfilled
     * again once soft-deleted, so the single delete refuses with the
     * classified INVALID through the SAME locked read the happy path
     * rides.
     */
    @Test
    void aReservedVariantRefusesTheDeleteThroughTheClassifiedInvalid() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        // The reserved row is a MOCK entity — the reservation counter is
        // written by the repository's native UPDATE, not the entity's own
        // vocabulary (the B7 wall tests' own fixture shape).
        ProductVariant reserved = org.mockito.Mockito.mock(ProductVariant.class);
        UUID reservedId = java.util.UUID.randomUUID();
        when(reserved.getId()).thenReturn(reservedId);
        when(reserved.getProviderId()).thenReturn(ProviderIds.CALLER);
        when(reserved.getReservedQuantity()).thenReturn(5);
        when(variantRepository.findByIdForUpdate(reservedId)).thenReturn(Optional.of(reserved));

        ProductVariantMutationResult result = service.deleteVariant(reservedId, caller);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "id");
        verify(variantRepository, never()).delete(any(ProductVariant.class));
    }

    /**
     * The bulk shape of the wall (the 2026-11-05 #808 round): a reserved
     * row is skipped — neither deleted nor counted (the honest
     * affected-count law), while the free sibling still deletes.
     */
    @Test
    void theBulkDeleteSkipsTheReservedRowAndCountsOnlyTheDeleted() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant free = variantUnder(parent, "SKU-FREE");
        ProductVariant reserved = org.mockito.Mockito.mock(ProductVariant.class);
        UUID reservedId = java.util.UUID.randomUUID();
        when(reserved.getId()).thenReturn(reservedId);
        when(reserved.getProviderId()).thenReturn(ProviderIds.CALLER);
        when(reserved.getReservedQuantity()).thenReturn(3);
        when(variantRepository.findByIdForUpdate(free.getId())).thenReturn(Optional.of(free));
        when(variantRepository.findByIdForUpdate(reservedId)).thenReturn(Optional.of(reserved));

        int deleted = service.deleteVariants(
                java.util.List.of(free.getId(), reservedId), caller);

        assertThat(deleted).isEqualTo(1);
        verify(variantRepository).delete(free);
        verify(variantRepository, never()).delete(reserved);
    }

    /**
     * The 2026-11-05 #808 round (Minor · Data Integrity): the unknown
     * errorPolicy answers the classified per-item INVALID up front — the
     * bulkCreate root's own contract (never a silent partial save under
     * an unpronounceable policy).
     */
    @Test
    void theBulkUpdateRejectsTheUnknownPolicyPerItem() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");

        var outcomes = service.bulkUpdateVariants(parent.getId(),
                java.util.List.of(new ProductVariantBulkUpdateItem(row.getId(),
                        new ProductVariantUpdateCommand("SKU-2", null, null))),
                caller, "NOT_A_POLICY");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductVariantMutationError::field)
                .isEqualTo("errorPolicy");
        verify(variantRepository, never()).save(any(ProductVariant.class));
    }

    @Test
    void anUpdateOfAVariantWhoseParentIsSoftDeletedAnswersTheClassifiedNotFound() {
        // The wave-19 #808 review (Major · Stability): the product's soft
        // delete takes the variant's shelf away — the projection answers
        // the classified NOT_FOUND (the read side's own safe-absence
        // shape), never the parent-absent NoSuchElementException throw
        // the raw orElseThrow produced.
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-ORPHAN");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.empty());

        ProductVariantMutationResult result = service.updateVariant(row.getId(),
                new ProductVariantUpdateCommand("Renamed", null, null), caller);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.NOT_FOUND, "id");
        verify(variantRepository, never()).save(any(ProductVariant.class));
    }

    @Test
    void aDeleteOfAVariantWhoseParentIsSoftDeletedAnswersTheClassifiedNotFound() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-ORPHAN");
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.empty());

        ProductVariantMutationResult result = service.deleteVariant(row.getId(), caller);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.NOT_FOUND, "id");
        verify(variantRepository, never()).delete(any(ProductVariant.class));
    }

    @Test
    void theBulkDeleteAnswersTheHonestCountOverUnknownAndStrangerIds() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant owned = variantUnder(parent, "SKU-1");
        ProductVariant strangerOwned = variantUnder(productOwnedBy(ProviderIds.STRANGER), "SKU-9");
        UUID unknown = UUID.randomUUID();
        when(variantRepository.findByIdForUpdate(owned.getId())).thenReturn(Optional.of(owned));
        when(variantRepository.findByIdForUpdate(strangerOwned.getId())).thenReturn(Optional.of(strangerOwned));
        when(variantRepository.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        int count = service.deleteVariants(
                List.of(owned.getId(), strangerOwned.getId(), unknown), caller);

        assertThat(count).isEqualTo(1);
        verify(variantRepository).delete(owned);
        verify(variantRepository, never()).delete(strangerOwned);
    }

    @Test
    void theBulkDeleteOfAnEmptyListAnswersZeroWithoutAWrite() {
        assertThat(service.deleteVariants(List.of(), caller)).isZero();
        assertThat(service.deleteVariants(null, caller)).isZero();
        verify(variantRepository, never()).delete(any(ProductVariant.class));
    }
    // ------------------------------------------------------------------
    // the bulk roots — the pinned policy's own semantics (the agent-2
    // follow-on packet)
    // ------------------------------------------------------------------

    @Test
    void bulkCreateReturnsThePerItemOutcomes() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-A")).thenReturn(0L);
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-B")).thenReturn(0L);
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-9")).thenReturn(1L);
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var outcomes = service.bulkCreateVariants(parent.getId(), List.of(
                new ProductVariantCreateCommand(null, "SKU-A", "A", 100L),
                new ProductVariantCreateCommand(null, "SKU-B", "B", 200L),
                new ProductVariantCreateCommand(null, "SKU-9", "Nine", 300L)),
                caller, "IGNORE_FAILED");

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).success()).isTrue();
        assertThat(outcomes.get(1).success()).isTrue();
        assertThat(outcomes.get(2).success()).isFalse();
        assertThat(outcomes.get(2).errors()).singleElement()
                .extracting(ProductVariantMutationError::code)
                .isEqualTo(ProductVariantMutationErrorCode.ALREADY_EXISTS);
        // every item registers under the pinned root's own product
        verify(variantRepository, org.mockito.Mockito.times(2)).save(argThat((ProductVariant row) ->
                row.getProductId().equals(parent.getId())));
    }

    @Test
    void bulkUpdateReturnsThePerItemOutcomes() {
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        // The bulk loop's routing pre-check reads the plain findById (a
        // cheap ownership filter); the authoritative re-read inside
        // updateVariant rides the locked findByIdForUpdate — the
        // membership probe is the round-19 PROJECTION (never the entity:
        // the cached pre-lock state defect), and the locked re-read
        // serves the update itself (the 2026-11-05 #808 round's locked
        // update).
        when(variantRepository.existsByIdAndProductId(row.getId(), parent.getId())).thenReturn(true);
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(variantRepository.countSkuReservationsIncludingDeletedExcluding(
                ProviderIds.CALLER, "SKU-2", row.getId())).thenReturn(0L);
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        var outcomes = service.bulkUpdateVariants(parent.getId(), List.of(
                new ProductVariantBulkUpdateItem(row.getId(),
                        new ProductVariantUpdateCommand("SKU-2", "Renamed", 500L)),
                new ProductVariantBulkUpdateItem(UUID.randomUUID(),
                        new ProductVariantUpdateCommand("X", null, null))),
                caller, "IGNORE_FAILED");

        assertThat(outcomes.get(0).success()).isTrue();
        assertThat(outcomes.get(1).success()).isFalse();
        assertThat(outcomes.get(1).errors()).singleElement()
                .extracting(ProductVariantMutationError::code)
                .isEqualTo(ProductVariantMutationErrorCode.NOT_FOUND);
    }

    @Test
    void bulkUpdateRefusesAVariantOfAnotherProductAsThePerRowNotFound() {
        // The shard review (2026-11-01, Minor · Data Integrity): the Saleor
        // product argument is a binding ownership claim — an item whose row
        // belongs to ANOTHER product answers the per-row NOT_FOUND, never a
        // cross-product amendment.
        Product parent = productOwnedBy(ProviderIds.CALLER);
        Product otherParent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant strangerRow = variantUnder(otherParent, "SKU-OTHER");

        var outcomes = service.bulkUpdateVariants(parent.getId(), List.of(
                new ProductVariantBulkUpdateItem(strangerRow.getId(),
                        new ProductVariantUpdateCommand("SKU-STOLEN", null, null))),
                caller, "IGNORE_FAILED");

        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductVariantMutationError::code)
                .isEqualTo(ProductVariantMutationErrorCode.NOT_FOUND);
        // The 2026-11-07 #808 round-19 review: the refusal rides the
        // SINGLE path's own vocabulary — ('id', NOT_FOUND, "No product
        // variant found for the id") — the previous ('product',
        // NOT_FOUND) shape distinguished a foreign-owned variant under a
        // valid product from an unknown pair (an association/existence
        // oracle the safe-absence law forbids).
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductVariantMutationError::field)
                .isEqualTo("id");
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductVariantMutationError::message)
                .isEqualTo("No product variant found for the id");
        verify(variantRepository, never()).findById(any());
    }

    @Test
    void bulkUpdateAnswersTheClassifiedRequiredForNullIdAndNullFields() {
        // The 2026-11-06 #808 round-3 review (Minor · Stability &
        // Availability): the per-item REQUIRED guards fire BEFORE any
        // repository call — a null id never reaches Spring Data's
        // findById (IllegalArgumentException) and a null fields command
        // never reaches updateVariant's isEmpty() (NullPointerException).
        Product parent = productOwnedBy(ProviderIds.CALLER);

        var outcomes = service.bulkUpdateVariants(parent.getId(), List.of(
                new ProductVariantBulkUpdateItem(null,
                        new ProductVariantUpdateCommand("SKU-A", null, null)),
                new ProductVariantBulkUpdateItem(UUID.randomUUID(), null)),
                caller, "IGNORE_FAILED");

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "id");
        assertThat(outcomes.get(1).success()).isFalse();
        assertThat(outcomes.get(1).errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "fields");
        verify(variantRepository, never()).findById(any());
    }

    @Test
    void bulkCreateVariantsWithAnUnknownPolicyRefusesEveryItemWithoutAnyWrite() {
        // The wave-19 #808 review (Minor): an unknown policy name is the
        // classified INVALID refusal for every item — never the silent
        // non-atomic persistence path.
        Product parent = productOwnedBy(ProviderIds.CALLER);

        var outcomes = service.bulkCreateVariants(parent.getId(), List.of(
                new ProductVariantCreateCommand(null, "SKU-A", "A", 100L)),
                caller, "reject_everything");

        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).variant()).isNull();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.INVALID, "errorPolicy");
        verify(variantRepository, never()).save(any(ProductVariant.class));
    }

    @Test
    void rejectEverythingRollbackRewritesTheSuccessfulVariantOutcomesWithoutAProjection() {
        // The wave-19 #808 review (Major): the rollback erased every row
        // the passing items claim — the outcomes are rebuilt with no view
        // and the batch's own classified refusal.
        Product parent = productOwnedBy(ProviderIds.CALLER);
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-A")).thenReturn(0L);
        when(variantRepository.countSkuReservationsIncludingDeleted(ProviderIds.CALLER, "SKU-9")).thenReturn(1L);
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        try (MockedStatic<TransactionAspectSupport> transaction =
                     org.mockito.Mockito.mockStatic(TransactionAspectSupport.class)) {
            transaction.when(TransactionAspectSupport::currentTransactionStatus)
                    .thenReturn(mock(TransactionStatus.class));

            var outcomes = service.bulkCreateVariants(parent.getId(), List.of(
                    new ProductVariantCreateCommand(null, "SKU-A", "A", 100L),
                    new ProductVariantCreateCommand(null, "SKU-9", "Nine", 300L)),
                    caller, "REJECT_EVERYTHING");

            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).success()).isFalse();
            assertThat(outcomes.get(0).variant()).isNull();
            assertThat(outcomes.get(0).errors()).singleElement()
                    .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                    .containsExactly(ProductVariantMutationErrorCode.INVALID, "errorPolicy");
            assertThat(outcomes.get(1).success()).isFalse();
            assertThat(outcomes.get(1).errors()).singleElement()
                    .extracting(ProductVariantMutationError::code)
                    .isEqualTo(ProductVariantMutationErrorCode.ALREADY_EXISTS);
        }
    }

    @Test
    void bulkUpdateProbesTheMembershipWithTheProjectionNeverTheEntity() {
        // The 2026-11-07 #808 round-19 review (Minor · Data Integrity &
        // Integration): the membership probe never LOADS the entity — the
        // previous unlocked findById put the row into the persistence
        // context BEFORE the single path's findByIdForUpdate, and the
        // locking query on an already-managed entity returns the CACHED
        // pre-lock state. The projection probe + the locked re-read keep
        // the drop-id/lock/load discipline.
        Product parent = productOwnedBy(ProviderIds.CALLER);
        ProductVariant row = variantUnder(parent, "SKU-1");
        when(variantRepository.existsByIdAndProductId(row.getId(), parent.getId())).thenReturn(true);
        when(variantRepository.findByIdForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(variantRepository.countSkuReservationsIncludingDeletedExcluding(
                ProviderIds.CALLER, "SKU-2", row.getId())).thenReturn(0L);
        when(variantRepository.save(any(ProductVariant.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        var outcomes = service.bulkUpdateVariants(parent.getId(), List.of(
                new ProductVariantBulkUpdateItem(row.getId(),
                        new ProductVariantUpdateCommand("SKU-2", null, null))),
                caller, "IGNORE_FAILED");

        assertThat(outcomes.get(0).success()).isTrue();
        verify(variantRepository, never()).findById(any());
    }

    @Test
    void bulkCreateAnswersTheClassifiedRequiredForANullElement() {
        // The 2026-11-07 #808 round-19 review (Minor · Functional
        // Correctness): a null element answers the per-item REQUIRED
        // error (the bulk-update path's own law) — never the
        // NullPointerException that aborts the whole batch.
        Product parent = productOwnedBy(ProviderIds.CALLER);

        var outcomes = service.bulkCreateVariants(parent.getId(),
                java.util.Arrays.asList((ProductVariantCreateCommand) null),
                caller, "IGNORE_FAILED");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductVariantMutationError::code, ProductVariantMutationError::field)
                .containsExactly(ProductVariantMutationErrorCode.REQUIRED, "command");
        verify(variantRepository, never()).save(any(ProductVariant.class));
    }

}
