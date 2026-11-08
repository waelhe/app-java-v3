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
 * absence and soft-delete happy path, the B7 open-reservation wall
 * (including its TOCTOU residual: the locked re-read refuses when a
 * reservation lands after the initial unlocked read), and the bulk
 * delete's honest count (stranger ids skipped, never fabricated).</p>
 */
class ProductWriteServiceTest {

    private ProductRepository productRepository;
    private StoreCategoryRepository storeCategoryRepository;
    private ProductTypeRepository productTypeRepository;
    private ProductVariantRepository variantRepository;
    private CurrentUserProvider currentUserProvider;
    /** The 2026-11-07 #808 round-19: the delete's membership cascade probe. */
    private CollectionProductRepository collectionProducts;
    private ProductWriteSpi service;
    private final Authentication caller = new TestingAuthenticationToken("provider-subject", "n/a", "ROLE_PROVIDER");

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        storeCategoryRepository = mock(StoreCategoryRepository.class);
        productTypeRepository = mock(ProductTypeRepository.class);
        variantRepository = mock(ProductVariantRepository.class);
        currentUserProvider = mock(CurrentUserProvider.class);
        // The 2026-11-07 #808 round-19: the delete's membership cascade
        // (the variants' cascade twin) — empty by default so every
        // existing ladder test stays intact.
        collectionProducts = mock(CollectionProductRepository.class);
        when(collectionProducts.findByProductIdOrderByPositionAsc(any())).thenReturn(List.of());
        service = new ProductWriteService(productRepository, storeCategoryRepository,
                productTypeRepository, variantRepository, currentUserProvider, collectionProducts);
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
    void malformedCurrencyAnswersTheInvalidDomainError() {
        // The 2026-11-05 #809 round (Minor · Test Quality): a SUPPLIED
        // but malformed value ("riyals") is the INVALID leg — REQUIRED
        // is the absent-field family (the sibling tests below); the
        // previous expectation misclassified the shape against the
        // ProductMutationErrorCode vocabulary's own description.
        ProductMutationResult result = service.createProduct(
                create("Widget", null, "widgets", 1000L, "riyals", null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, "currency");
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

    /**
     * B21 (CAT-09 — the 2026-11-03 adversarial review, review-pr-808): the
     * create's type validation rides the LOCKING read — FOR SHARE, held
     * for the creating transaction's lifetime (the delete side's
     * FOR UPDATE twin serializes against it) — never the plain findById
     * whose verdict a concurrent productTypeDelete could invalidate
     * between the check and the commit (the soft delete leaves the
     * physical row the FK still accepts, so the FK alone never catches
     * the window).
     */
    @Test
    void theCreateValidatesTheTypeThroughTheLockingRead() {
        when(storeCategoryRepository.findByCode("widgets")).thenReturn(Optional.of(mock(StoreCategory.class)));
        UUID typeId = UUID.randomUUID();
        when(productTypeRepository.findByIdForShare(typeId)).thenReturn(Optional.empty());

        ProductMutationResult result = service.createProduct(
                create("Widget", null, "widgets", 1000L, "SAR", typeId), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.NOT_FOUND, "productType");
        verify(productTypeRepository).findByIdForShare(typeId);
        verify(productTypeRepository, never()).findById(typeId);
        verify(productRepository, never()).save(any());
    }

    @Test
    void unknownProductTypeAnswersTheNotFoundDomainError() {
        when(storeCategoryRepository.findByCode("widgets")).thenReturn(Optional.of(mock(StoreCategory.class)));
        UUID unknownType = UUID.randomUUID();
        when(productTypeRepository.findByIdForShare(unknownType)).thenReturn(Optional.empty());

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
        when(productTypeRepository.findByIdForShare(typeId)).thenReturn(Optional.of(mock(ProductType.class)));
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
        when(productRepository.findByIdAndProviderIdForUpdate(unknown, ProviderIds.CALLER)).thenReturn(Optional.empty());

        ProductMutationResult result = service.updateProduct(unknown,
                new ProductUpdateCommand("New", null, null), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void aStrangersRowAnswersTheSameSafeAbsence() {
        Product strangers = rowOwnedBy(ProviderIds.STRANGER);
        // The scoped locked load answers EMPTY for a stranger's row (the provider
        // predicate rides INSIDE the query — the round-21 law).
        when(productRepository.findByIdAndProviderIdForUpdate(strangers.getId(), ProviderIds.CALLER)).thenReturn(Optional.empty());

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
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));

        ProductMutationResult result = service.updateProduct(owned.getId(),
                new ProductUpdateCommand(null, null, null), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.product().name()).isEqualTo("Owned Widget");
        verify(productRepository, never()).save(any());
    }

    @Test
    void providedFieldsReplaceAndNullsPreserve() {
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));
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
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));

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
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));

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
        when(productRepository.findByIdAndProviderIdForUpdate(archived.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(archived));

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
        // The delete path's locked read (the voucher fixtures' own
        // findByIdForUpdate stub shape) — the stranger row still answers
        // the same safe absence through it.
        // The scoped locked load answers EMPTY for a stranger's row (the provider
        // predicate rides INSIDE the query — the round-21 law).
        when(productRepository.findByIdAndProviderIdForUpdate(strangers.getId(), ProviderIds.CALLER)).thenReturn(Optional.empty());

        ProductMutationResult result = service.deleteProduct(strangers.getId(), caller);

        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code)
                .isEqualTo(ProductMutationErrorCode.NOT_FOUND);
        verify(productRepository, never()).delete(any(Product.class));
    }

    @Test
    void deleteAnswersTheLastKnownProjection() {
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));

        ProductMutationResult result = service.deleteProduct(owned.getId(), caller);

        assertThat(result.success()).isTrue();
        assertThat(result.product().name()).isEqualTo("Owned Widget");
        verify(productRepository).delete(owned);
    }

    @Test
    void deleteCascadesTheProductVariantsWithTheProduct() {
        // The wave-19 #808 review's preferred law (Major · Stability): the
        // variants' shelf IS the product's — a deleted product's live
        // variants cascade to the same soft delete, never left as an
        // owned orphan shelf the write side still reaches.
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        ProductVariant variant = ProductVariant.register(owned, "orphan-sku", "Orphan", 100L);
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));
        when(variantRepository.findByProductIdOrderByIdAscForUpdate(owned.getId()))
                .thenReturn(List.of(variant));

        ProductMutationResult result = service.deleteProduct(owned.getId(), caller);

        assertThat(result.success()).isTrue();
        verify(productRepository).delete(owned);
        verify(variantRepository).delete(variant);
    }

    @Test
    void deleteOfAReservedProductAnswersTheClassifiedRefusal() {
        // B7 (CAT-04 — the 2026-11-03 adversarial review): the
        // open-reservation wall — a soft delete under an open reservation
        // is unreleasable forever (the U1 probe's measured truth), so the
        // delete answers the classified INVALID refusal instead.
        Product reserved = mock(Product.class);
        when(reserved.getId()).thenReturn(UUID.randomUUID());
        when(reserved.getProviderId()).thenReturn(ProviderIds.CALLER);
        when(reserved.getReservedQuantity()).thenReturn(5);
        when(productRepository.findByIdAndProviderIdForUpdate(reserved.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(reserved));

        ProductMutationResult result = service.deleteProduct(reserved.getId(), caller);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code)
                .isEqualTo(ProductMutationErrorCode.INVALID);
        verify(productRepository, never()).delete(any(Product.class));
        verify(variantRepository, never()).delete(any(ProductVariant.class));
    }

    @Test
    void deleteOfAProductWhoseVariantHoldsAReservationAnswersTheRefusal() {
        // The wall spans BOTH shelves (ADR-0012's reservation spans the
        // product and its variants; the delete cascades the variant shelf,
        // so an open variant reservation is the same unreleasable state).
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        ProductVariant reservedVariant = mock(ProductVariant.class);
        when(reservedVariant.getReservedQuantity()).thenReturn(2);
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));
        when(variantRepository.findByProductIdOrderByIdAscForUpdate(owned.getId()))
                .thenReturn(List.of(reservedVariant));

        ProductMutationResult result = service.deleteProduct(owned.getId(), caller);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code)
                .isEqualTo(ProductMutationErrorCode.INVALID);
        verify(productRepository, never()).delete(any(Product.class));
        verify(variantRepository, never()).delete(any(ProductVariant.class));
    }

    /**
     * B7's TOCTOU residual (CAT-04 — the 2026-11-04 follow-on): the
     * wall's first shape was check-then-act. The unlocked read (the
     * first stub below) saw reserved=0; a concurrent placement's
     * reservation — a bulk UPDATE that never touches the @Version
     * column — committed under it; the LOCKED re-read the delete's
     * guard now rides sees reserved=5. The wall must hold on the LOCKED
     * row: the classified INVALID refusal answers and the delete never
     * reaches the registry (a soft delete under an open reservation is
     * the stranded U1 state, exactly what B7 exists to prevent).
     */
    @Test
    void deleteRefusesWhenAReservationLandsAfterTheInitialUnlockedRead() {
        // The raced row's id is a plain local: a mock's getId() inside
        // thenReturn(...) clobbers the pending stubbing (Mockito's
        // documented unfinished-stubbing race — the same class the
        // CollectionReadServiceTest round caught).
        UUID raceId = UUID.randomUUID();
        Product staleRead = mock(Product.class);
        when(staleRead.getId()).thenReturn(raceId);
        when(staleRead.getProviderId()).thenReturn(ProviderIds.CALLER);
        when(staleRead.getReservedQuantity()).thenReturn(0);
        Product lockedRow = mock(Product.class);
        when(lockedRow.getId()).thenReturn(raceId);
        when(lockedRow.getProviderId()).thenReturn(ProviderIds.CALLER);
        when(lockedRow.getReservedQuantity()).thenReturn(5);
        // The stale unlocked read the pre-lock guard used to consult (kept
        // as the race's own witness — the delete path no longer reads
        // through it). The 2026-11-05 #809 round: this witness used to
        // stub findByIdForUpdate TOO — Mockito's last-wins silently
        // replaced it with the locked row, so the race's stale half was
        // never actually served to any read; the unlocked findById stub
        // restores the raced two-read shape the test exists to prove.
        when(productRepository.findById(raceId)).thenReturn(Optional.of(staleRead));
        // The locked read the delete path now rides.
        when(productRepository.findByIdAndProviderIdForUpdate(raceId, ProviderIds.CALLER)).thenReturn(Optional.of(lockedRow));

        ProductMutationResult result = service.deleteProduct(raceId, caller);
        // The delete path consulted the LOCKED scoped read (the stale unlocked
        // witness never reaches the decision; the provider predicate rides
        // INSIDE the locking query — the round-21 law).
        verify(productRepository).findByIdAndProviderIdForUpdate(raceId, ProviderIds.CALLER);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(ProductMutationError::code)
                .isEqualTo(ProductMutationErrorCode.INVALID);
        verify(productRepository, never()).delete(any(Product.class));
        verify(variantRepository, never()).delete(any(ProductVariant.class));
    }

    @Test
    void bulkDeleteAnswersTheHonestOwnedCount() {
        Product first = rowOwnedBy(ProviderIds.CALLER);
        Product strangers = rowOwnedBy(ProviderIds.STRANGER);
        // The bulk candidates ride the same locked read the single delete
        // owns (the per-row guard checks the LOCKED counters).
        when(productRepository.findByIdAndProviderIdForUpdate(first.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(first));
        // The scoped locked load answers EMPTY for a stranger's row (the provider
        // predicate rides INSIDE the query — the round-21 law).
        when(productRepository.findByIdAndProviderIdForUpdate(strangers.getId(), ProviderIds.CALLER)).thenReturn(Optional.empty());

        // Arrays.asList carries the null member the honest-skip case
        // feeds the owner (List.of's own null refusal is not the seam).
        int count = service.deleteProducts(java.util.Arrays.asList(
                first.getId(), strangers.getId(), UUID.randomUUID(), null), caller);

        assertThat(count).isEqualTo(1);
        verify(productRepository).delete(first);
        verify(productRepository, never()).delete(strangers);
    }

    @Test
    void bulkDeleteTakesTheProductLocksInSortedOrder() {
        // The 2026-11-08 #808 round-23 review (Minor · Stability &
        // Availability): the product row locks come in ONE sorted pass —
        // the caller's list order let two concurrent bulk deletes naming
        // the same rows in opposite orders meet the deadlock detector.
        // The 80-row's signed-long msb makes it sort FIRST in Java's
        // natural order while the 0f row sorts first in the hex order —
        // the pass must lock the NATURAL order (the module's bulk
        // convention), whatever order the caller named.
        UUID late = UUID.fromString("0f000000-0000-0000-0000-000000000000");
        UUID early = UUID.fromString("80000000-0000-0000-0000-000000000000");
        when(productRepository.findByIdAndProviderIdForUpdate(any(UUID.class), any(UUID.class)))
                .thenReturn(java.util.Optional.empty());

        service.deleteProducts(java.util.Arrays.asList(late, early), caller);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(productRepository);
        order.verify(productRepository).findByIdAndProviderIdForUpdate(early, ProviderIds.CALLER);
        order.verify(productRepository).findByIdAndProviderIdForUpdate(late, ProviderIds.CALLER);
    }

    @Test
    void bulkDeleteSkipsTheReservedRowWithoutCountingIt() {
        // B7 (CAT-04): the bulk shape of the open-reservation wall — the
        // blocked row is neither deleted nor counted (the count answers
        // only what the registry itself deleted).
        Product reserved = mock(Product.class);
        when(reserved.getId()).thenReturn(UUID.randomUUID());
        when(reserved.getProviderId()).thenReturn(ProviderIds.CALLER);
        when(reserved.getReservedQuantity()).thenReturn(5);
        Product free = rowOwnedBy(ProviderIds.CALLER);
        when(productRepository.findByIdAndProviderIdForUpdate(reserved.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(reserved));
        when(productRepository.findByIdAndProviderIdForUpdate(free.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(free));

        int count = service.deleteProducts(List.of(reserved.getId(), free.getId()), caller);

        assertThat(count).as("the reserved row is skipped and not counted").isEqualTo(1);
        verify(productRepository, never()).delete(reserved);
        verify(productRepository).delete(free);
    }

    @Test
    void bulkDeleteOfAnEmptyListAnswersZero() {
        assertThat(service.deleteProducts(List.of(), caller)).isZero();
        assertThat(service.deleteProducts(null, caller)).isZero();
        verify(productRepository, never()).delete(any(Product.class));
    }
    // ------------------------------------------------------------------
    // the bulk create root — the pinned policy's own semantics (the
    // agent-2 follow-on packet)
    // ------------------------------------------------------------------

    @Test
    void bulkCreateReturnsThePerItemOutcomesWithTheCallerAsEveryRowsOwner() {
        when(storeCategoryRepository.findByCode("widgets")).thenReturn(Optional.of(mock(StoreCategory.class)));
        when(storeCategoryRepository.findByCode("missing")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var outcomes = service.bulkCreateProducts(List.of(
                create("Bulk Widget One", null, "widgets", 1000L, "SAR", null),
                create("Bulk Widget Two", null, "widgets", 2000L, "SAR", null),
                create("Bulk Missing", null, "missing", 1000L, "SAR", null)),
                caller, "IGNORE_FAILED");

        assertThat(outcomes).hasSize(3);
        assertThat(outcomes.get(0).success()).isTrue();
        assertThat(outcomes.get(1).success()).isTrue();
        assertThat(outcomes.get(2).success()).isFalse();
        assertThat(outcomes.get(2).errors()).singleElement()
                .extracting(ProductMutationError::code)
                .isEqualTo(ProductMutationErrorCode.NOT_FOUND);
        verify(productRepository, org.mockito.Mockito.times(2)).save(any(Product.class));
    }

    @Test
    void bulkCreateWithAnUnknownPolicyRefusesEveryItemWithoutAnyWrite() {
        var outcomes = service.bulkCreateProducts(List.of(
                create("Bulk Widget One", null, "widgets", 1000L, "SAR", null)),
                caller, "reject_everything");

        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).product()).isNull();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.INVALID, "errorPolicy");
        verify(productRepository, never()).save(any(Product.class));
    }

    @Test
    void rejectEverythingRollbackRebuildsTheSuccessfulOutcomesWithoutAView() {
        // The wave-19 #808 review (Major): the rollback erased every row
        // the passing items claim — the outcomes are rebuilt with no view
        // and the batch's own classified refusal (the Saleor
        // REJECT_EVERYTHING contract: no item returns a saved entity).
        when(storeCategoryRepository.findByCode("widgets")).thenReturn(Optional.of(mock(StoreCategory.class)));
        when(storeCategoryRepository.findByCode("missing")).thenReturn(Optional.empty());
        when(productRepository.save(any(Product.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        try (MockedStatic<TransactionAspectSupport> transaction =
                     org.mockito.Mockito.mockStatic(TransactionAspectSupport.class)) {
            transaction.when(TransactionAspectSupport::currentTransactionStatus)
                    .thenReturn(mock(TransactionStatus.class));

            var outcomes = service.bulkCreateProducts(List.of(
                    create("Bulk Widget Ok", null, "widgets", 1000L, "SAR", null),
                    create("Bulk Missing", null, "missing", 1000L, "SAR", null)),
                    caller, "REJECT_EVERYTHING");

            assertThat(outcomes).hasSize(2);
            assertThat(outcomes.get(0).success()).isFalse();
            assertThat(outcomes.get(0).product()).isNull();
            assertThat(outcomes.get(0).errors()).singleElement()
                    .extracting(ProductMutationError::code, ProductMutationError::field)
                    .containsExactly(ProductMutationErrorCode.INVALID, "errorPolicy");
            assertThat(outcomes.get(1).success()).isFalse();
            assertThat(outcomes.get(1).errors()).singleElement()
                    .extracting(ProductMutationError::code)
                    .isEqualTo(ProductMutationErrorCode.NOT_FOUND);
        }
    }

    @Test
    void deleteCascadesTheCollectionMembershipsWithTheProduct() {
        // The 2026-11-07 #808 round-19 review (Minor · Data Integrity &
        // Integration): the membership's shelf IS the product's — the
        // variants' cascade twin. Without the cascade a soft-deleted
        // product left LIVE membership rows the read join hid while the
        // reorder still walked them.
        Product owned = rowOwnedBy(ProviderIds.CALLER);
        CollectionProduct member = CollectionProduct.create(
                UUID.randomUUID(), owned.getId(), 1);
        when(productRepository.findByIdAndProviderIdForUpdate(owned.getId(), ProviderIds.CALLER)).thenReturn(Optional.of(owned));
        when(collectionProducts.findByProductIdOrderByPositionAsc(owned.getId()))
                .thenReturn(List.of(member));

        ProductMutationResult result = service.deleteProduct(owned.getId(), caller);

        assertThat(result.success()).isTrue();
        verify(collectionProducts).delete(member);
    }

    @Test
    void bulkCreateAnswersTheClassifiedRequiredForANullElement() {
        // The 2026-11-07 #808 round-19 review (Minor · Functional
        // Correctness): a null element answers the per-item REQUIRED
        // error (the bulk-update path's own law) — never the
        // NullPointerException that aborts the whole batch.
        var outcomes = service.bulkCreateProducts(
                java.util.Arrays.asList((ProductCreateCommand) null), caller, "IGNORE_FAILED");

        assertThat(outcomes).hasSize(1);
        assertThat(outcomes.get(0).success()).isFalse();
        assertThat(outcomes.get(0).errors()).singleElement()
                .extracting(ProductMutationError::code, ProductMutationError::field)
                .containsExactly(ProductMutationErrorCode.REQUIRED, "command");
        verify(productRepository, never()).save(any(Product.class));
    }

}
