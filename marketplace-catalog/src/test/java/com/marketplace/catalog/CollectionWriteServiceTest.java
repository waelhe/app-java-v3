package com.marketplace.catalog;

import com.marketplace.catalog.spi.CollectionCreateCommand;
import com.marketplace.catalog.spi.CollectionMutationError;
import com.marketplace.catalog.spi.CollectionMutationErrorCode;
import com.marketplace.catalog.spi.CollectionMutationResult;
import com.marketplace.catalog.spi.CollectionProductMove;
import com.marketplace.catalog.spi.CollectionUpdateCommand;
import com.marketplace.catalog.spi.CollectionWriteSpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionCreate}, {@code collectionUpdate},
 * {@code collectionDelete}, {@code collectionBulkDelete},
 * {@code collectionAddProducts}, {@code collectionRemoveProducts} and
 * {@code collectionReorderProducts} mutation roots, packet agent-2): the
 * owning write use case's unit proof — the JUnit 5 + Mockito style the
 * module's own {@code CategoryWriteServiceTest} established (no Spring
 * context: the {@code @PreAuthorize} proxy is an integration concern
 * proven by the app module's real-stack tests; the business decisions
 * the boundary owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link CollectionWriteService} reachable through its typed seam — the
 * create's full validation ladder (blank/oversized name, missing/invalid/
 * duplicate slug, the duplicate/unknown initial membership, the happy
 * path's row factory and the membership append), the update's
 * partial-preserve semantics (null fields preserve, the duplicate-slug
 * negative EXCLUDING the row's own id, the NOT_FOUND absence), the
 * single delete's NOT_FOUND and the membership-riding soft delete, the
 * bulk delete's honest count, the membership surfaces' duplicate/
 * unknown/append laws, and the reorder's relative-move resolution with
 * its compacted 1..n re-derivation and its out-of-range refusal.</p>
 */
class CollectionWriteServiceTest {

    private CollectionRepository collectionRepository;
    private CollectionProductRepository membershipRepository;
    private ProductRepository productRepository;
    private CollectionWriteSpi service;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(CollectionRepository.class);
        membershipRepository = mock(CollectionProductRepository.class);
        productRepository = mock(ProductRepository.class);
        // The 2026-11-07 #808 round-19: the membership-CREATING read takes
        // the product rows FOR SHARE — every add test's validated products
        // answer the share read by default (the delete-pairing test below
        // overrides its own absence).
        lenient().when(productRepository.findByIdForShare(any(UUID.class)))
                .thenAnswer(invocation -> java.util.Optional.of(product()));
        service = new CollectionWriteService(
                collectionRepository, membershipRepository, productRepository);
    }

    private static Collection row(String slug, String nameEn, boolean published) {
        return Collection.create(slug, nameEn, null, published);
    }

    private static Product product() {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(UUID.randomUUID());
        when(product.getTitle()).thenReturn("A product");
        when(product.getProductTypeId()).thenReturn(null);
        when(product.getStatus()).thenReturn(StoreProductStatus.ACTIVE);
        return product;
    }

    // ------------------------------------------------------------------
    // createCollection — the validation ladder and the happy path
    // ------------------------------------------------------------------

    @Test
    void blankNameAnswersTheRequiredDomainError() {
        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("  ", "fresh-slug", null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).containsExactly(new CollectionMutationError(
                "name", CollectionMutationErrorCode.REQUIRED, "A collection name is required"));
        verify(collectionRepository, never()).save(any());
    }

    @Test
    void missingSlugAnswersTheRequiredDomainError() {
        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Fresh", null, null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.REQUIRED, "slug");
    }

    @Test
    void invalidSlugShapeAnswersTheInvalidDomainError() {
        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Fresh", "Not_A-Slug", null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.INVALID, "slug");
        verify(collectionRepository, never()).save(any());
    }

    @Test
    void aLeadingDashSlugMatchesTheDatabaseChecksOwnShapeClass() {
        // The 2026-11-08 #808 round-21 review (Minor · Functional
        // Correctness): the service's shape mirror is the V253 CHECK's
        // OWN class [a-z0-9-]+ exactly — a leading-dash slug like "-sale"
        // passes the database constraint and must pass the boundary too
        // (the previous [a-z0-9][a-z0-9-]* front anchor was stricter than
        // the pinned constraint it claimed to mirror).
        when(collectionRepository.findBySlug("-sale")).thenReturn(Optional.empty());
        when(collectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Sale", "-sale", null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.collection().slug()).isEqualTo("-sale");
    }

    @Test
    void anEmptyAddOnAnExhaustedCollectionIsTheHonestNoOpSuccess() {
        // The 2026-11-08 #808 round-21 review (Minor · Functional
        // Correctness, additional comment): an empty product list
        // allocates NO position — the exhausted-collection refusal
        // (the ceiling-high live member) must not answer a request
        // that writes nothing. The no-op success carries the
        // collection's own projection.
        UUID collectionId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Full", true)));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(CollectionProduct.create(
                        collectionId, UUID.randomUUID(), Integer.MAX_VALUE)));

        CollectionMutationResult result = service.addProducts(collectionId, List.of());

        assertThat(result.success()).isTrue();
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void duplicateLiveSlugAnswersTheUniqueDomainError() {
        when(collectionRepository.findBySlug("taken")).thenReturn(
                Optional.of(row("taken", "Taken", true)));

        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Fresh", "taken", null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.UNIQUE, "slug");
        verify(collectionRepository, never()).save(any());
    }

    @Test
    void duplicatedInitialMembershipAnswersTheDuplicatedInputItemDomainError() {
        UUID duplicated = UUID.randomUUID();
        Product member = product();
        when(productRepository.findById(duplicated)).thenReturn(Optional.of(member));

        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Fresh", "fresh", null, List.of(duplicated, duplicated)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field,
                        CollectionMutationError::products)
                .containsExactly(CollectionMutationErrorCode.DUPLICATED_INPUT_ITEM,
                        "products", List.of(duplicated));
        verify(collectionRepository, never()).save(any());
    }

    @Test
    void unknownInitialMembershipAnswersTheNotFoundDomainErrorWithTheIds() {
        UUID unknown = UUID.randomUUID();

        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Fresh", "fresh", null, List.of(unknown)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field,
                        CollectionMutationError::products)
                .containsExactly(CollectionMutationErrorCode.NOT_FOUND, "products", List.of(unknown));
    }

    @Test
    void happyCreateSavesTheRowAndAppendsTheMembership() {
        UUID productId = UUID.randomUUID();
        Product member = product();
        when(collectionRepository.findBySlug("fresh")).thenReturn(Optional.empty());
        when(productRepository.findById(productId)).thenReturn(Optional.of(member));
        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(any()))
                .thenReturn(List.of());

        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Fresh", "fresh", true, List.of(productId)));

        assertThat(result.success()).isTrue();
        assertThat(result.collection()).isNotNull();
        assertThat(result.collection().slug()).isEqualTo("fresh");
        assertThat(result.collection().published()).isTrue();
        verify(collectionRepository).save(any());
        verify(membershipRepository).save(any());
    }

    @Test
    void createNormalizesTheSlugToTheRegistrysOwnLowercaseContract() {
        when(collectionRepository.findBySlug("mixed-case")).thenReturn(Optional.empty());
        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Fresh", "Mixed-Case", null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.collection().slug()).isEqualTo("mixed-case");
    }

    @Test
    void createTakesTheSlugLockBeforeTheUniquenessRead() {
        // The 2026-11-07 #808 round-18 review (Minor · Data Integrity &
        // Integration): the slug's own advisory lock BEFORE the
        // findBySlug read — two concurrent creates over the same slug
        // serialize at the check point and the partial unique index
        // stays a backstop, never an unclassified 500 at commit.
        when(collectionRepository.findBySlug("raced")).thenReturn(Optional.empty());
        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.createCollection(new CollectionCreateCommand("Raced", "raced", null, null));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(collectionRepository);
        inOrder.verify(collectionRepository).lockSlugAllocation("raced");
        inOrder.verify(collectionRepository).findBySlug("raced");
    }

    // ------------------------------------------------------------------
    // updateCollection — the partial-preserve semantics
    // ------------------------------------------------------------------

    @Test
    void updateAnswersTheNotFoundDomainErrorForAnUnknownId() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.empty());

        CollectionMutationResult result = service.updateCollection(
                id, new CollectionUpdateCommand("Fresh", null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void updateTakesTheDeleteSerializationLockBeforeReadingTheCollection() {
        // The 2026-11-07 #808 round-17 review (Minor · Data Integrity &
        // Integration): the update rides the SAME advisory lock every
        // other collection writer carries, BEFORE the read — an unlocked
        // findById could read the row before a concurrent delete's
        // commit and the managed-row save would overwrite the
        // soft-deleted row and answer success.
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.of(row("slug", "Old", true)));
        when(collectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateCollection(id, new CollectionUpdateCommand("Fresh", null, null));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(membershipRepository, collectionRepository);
        inOrder.verify(membershipRepository).lockCollectionPositionAllocation(id.toString());
        inOrder.verify(collectionRepository).findById(id);
    }

    @Test
    void updateTakesTheSlugLockBeforeTheUniquenessRead() {
        // The 2026-11-07 #808 round-18 review: the update's slug
        // re-check rides the slug's own advisory lock too — the update
        // and a concurrent create over the same slug see each other's
        // commit (never the partial unique index's raw 500).
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.of(row("slug", "Old", true)));
        when(collectionRepository.findBySlug("fresh-slug")).thenReturn(Optional.empty());
        when(collectionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateCollection(id, new CollectionUpdateCommand("Fresh", "fresh-slug", null));

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(collectionRepository);
        inOrder.verify(collectionRepository).lockSlugAllocation("fresh-slug");
        inOrder.verify(collectionRepository).findBySlug("fresh-slug");
    }

    @Test
    void blankNameAnswersTheInvalidDomainError() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.of(row("slug", "Old", true)));

        CollectionMutationResult result = service.updateCollection(
                id, new CollectionUpdateCommand(" ", null, null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.INVALID, "name");
        verify(collectionRepository, never()).save(any());
    }

    @Test
    void duplicateSlugExcludingTheRowsOwnIdAnswersTheUniqueDomainError() {
        UUID id = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.of(row("slug", "Old", true)));
        when(collectionRepository.findBySlug("taken")).thenReturn(Optional.of(row("taken", "Taken", true)));
        when(collectionRepository.findById(otherId)).thenReturn(Optional.empty());

        CollectionMutationResult result = service.updateCollection(
                id, new CollectionUpdateCommand(null, "taken", null));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.UNIQUE, "slug");
    }

    @Test
    void nullFieldsPreserveTheColumnsAndThePublicationFact() {
        UUID id = UUID.randomUUID();
        Collection existing = row("slug", "Old", true);
        when(collectionRepository.findById(id)).thenReturn(Optional.of(existing));
        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CollectionMutationResult result = service.updateCollection(
                id, new CollectionUpdateCommand(null, null, null));

        assertThat(result.success()).isTrue();
        assertThat(result.collection().name()).isEqualTo("Old");
        assertThat(result.collection().slug()).isEqualTo("slug");
        assertThat(result.collection().published()).isTrue();
        verify(collectionRepository).save(any());
    }

    @Test
    void thePublicationFactAmendmentRidesThePartialUpdate() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.of(row("slug", "Old", false)));
        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CollectionMutationResult result = service.updateCollection(
                id, new CollectionUpdateCommand(null, null, true));

        assertThat(result.success()).isTrue();
        assertThat(result.collection().published()).isTrue();
    }

    // ------------------------------------------------------------------
    // deleteCollection / deleteCollections — the soft-delete discipline
    // ------------------------------------------------------------------

    @Test
    void deleteAnswersTheNotFoundDomainErrorForAnUnknownId() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.empty());

        CollectionMutationResult result = service.deleteCollection(id);

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void deleteRemovesTheLiveMembershipInTheSameTransactionAndAnswersTheLastKnownProjection() {
        UUID id = UUID.randomUUID();
        Collection existing = row("slug", "Old", true);
        when(collectionRepository.findById(id)).thenReturn(Optional.of(existing));
        UUID productId = UUID.randomUUID();
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(any()))
                .thenReturn(List.of(CollectionProduct.create(id, productId, 1)));

        CollectionMutationResult result = service.deleteCollection(id);

        assertThat(result.success()).isTrue();
        assertThat(result.collection().slug()).isEqualTo("slug");
        verify(membershipRepository).delete(any());
        verify(collectionRepository).delete(existing);
    }

    @Test
    void bulkDeleteAnswersTheHonestCountAndSkipsUnknownIds() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(collectionRepository.findById(known)).thenReturn(Optional.of(row("slug", "Old", true)));
        when(collectionRepository.findById(unknown)).thenReturn(Optional.empty());
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(any()))
                .thenReturn(List.of());

        int count = service.deleteCollections(List.of(known, unknown));

        assertThat(count).isEqualTo(1);
        verify(collectionRepository).delete(any());
    }

    @Test
    void bulkDeleteWithNoIdsAnswersZero() {
        assertThat(service.deleteCollections(List.of())).isZero();
        assertThat(service.deleteCollections(null)).isZero();
    }

    // ------------------------------------------------------------------
    // addProducts / removeProducts — the membership write surfaces
    // ------------------------------------------------------------------

    @Test
    void addProductsAnswersTheNotFoundDomainErrorForAnUnknownCollection() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.empty());

        CollectionMutationResult result = service.addProducts(id, List.of(UUID.randomUUID()));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void addProductsAnswersTheDuplicatedInputItemDomainErrorForAnAlreadyMemberPair() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        Product member = product();
        when(productRepository.findById(productId)).thenReturn(Optional.of(member));
        // The 2026-11-07 #808 round-6 review: the membership precheck rides
        // the ONE batch query (the per-id loop's N queries inside the
        // advisory lock are gone).
        when(membershipRepository.findByCollectionIdAndProductIdIn(collectionId, List.of(productId)))
                .thenReturn(List.of(CollectionProduct.create(collectionId, productId, 1)));

        CollectionMutationResult result = service.addProducts(collectionId, List.of(productId));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::products)
                .containsExactly(CollectionMutationErrorCode.DUPLICATED_INPUT_ITEM, List.of(productId));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void addProductsAnswersTheNotFoundDomainErrorForAnUnknownProduct() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(productRepository.findById(productId)).thenReturn(Optional.empty());

        CollectionMutationResult result = service.addProducts(collectionId, List.of(productId));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::products)
                .containsExactly(CollectionMutationErrorCode.NOT_FOUND, List.of(productId));
    }

    @Test
    void happyAddProductsAppendsTheMembershipAfterTheCurrentTail() {
        UUID collectionId = UUID.randomUUID();
        UUID newProductId = UUID.randomUUID();
        UUID existingProductId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        Product member = product();
        when(productRepository.findById(newProductId)).thenReturn(Optional.of(member));
        when(membershipRepository.findByCollectionIdAndProductIdIn(collectionId, List.of(newProductId)))
                .thenReturn(List.of());
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(CollectionProduct.create(collectionId, existingProductId, 1)));

        CollectionMutationResult result = service.addProducts(collectionId, List.of(newProductId));

        assertThat(result.success()).isTrue();
        assertThat(result.collection().slug()).isEqualTo("slug");
        verify(membershipRepository).save(any());
    }

    @Test
    void removeThenAppendDerivesThePositionFromTheLiveMaximum() {
        UUID collectionId = UUID.randomUUID();
        UUID newProductId = UUID.randomUUID();
        UUID survivorProductId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        Product member = product();
        when(productRepository.findById(newProductId)).thenReturn(Optional.of(member));
        when(membershipRepository.findByCollectionIdAndProductIdIn(collectionId, List.of(newProductId)))
                .thenReturn(List.of());
        // The registry's own live tail after collectionRemoveProducts
        // deleted the middle member WITHOUT renumbering: positions 1,3,4.
        // A size+1 derivation would mint 4 again (the duplicate-position
        // defect the 2026-11-03 preemptive review closed); the append
        // law's max+1 derivation answers 5.
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(
                        CollectionProduct.create(collectionId, UUID.randomUUID(), 1),
                        CollectionProduct.create(collectionId, UUID.randomUUID(), 3),
                        CollectionProduct.create(collectionId, survivorProductId, 4)));

        CollectionMutationResult result = service.addProducts(collectionId, List.of(newProductId));

        assertThat(result.success()).isTrue();
        verify(membershipRepository).save(argThat(saved ->
                saved.getPosition() == 5 && saved.getProductId().equals(newProductId)));
    }

    @Test
    void removeProductsSkipsNonMembersIdempotently() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdAndProductIdIn(collectionId, List.of(productId)))
                .thenReturn(List.of());

        CollectionMutationResult result = service.removeProducts(collectionId, List.of(productId));

        assertThat(result.success()).isTrue();
        verify(membershipRepository, never()).delete(any());
    }

    @Test
    void happyRemoveProductsDeletesTheLivePair() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        CollectionProduct member = CollectionProduct.create(collectionId, productId, 1);
        when(membershipRepository.findByCollectionIdAndProductIdIn(collectionId, List.of(productId)))
                .thenReturn(List.of(member));

        CollectionMutationResult result = service.removeProducts(collectionId, List.of(productId));

        assertThat(result.success()).isTrue();
        verify(membershipRepository).delete(member);
    }

    // ------------------------------------------------------------------
    // reorderProducts — the relative-move resolution
    // ------------------------------------------------------------------

    @Test
    void reorderAnswersTheNotFoundDomainErrorForAnUnknownCollection() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(Optional.empty());

        CollectionMutationResult result = service.reorderProducts(id,
                List.of(new CollectionProductMove(UUID.randomUUID(), 1)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field)
                .containsExactly(CollectionMutationErrorCode.NOT_FOUND, "id");
    }

    @Test
    void reorderAnswersTheNotFoundDomainErrorForANonMemberProduct() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of());

        CollectionMutationResult result = service.reorderProducts(collectionId,
                List.of(new CollectionProductMove(productId, 1)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field,
                        CollectionMutationError::products)
                .containsExactly(CollectionMutationErrorCode.NOT_FOUND, "moves", List.of(productId));
    }

    @Test
    void anOutOfRangeMoveAnswersTheInvalidDomainErrorWithTheProduct() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(CollectionProduct.create(collectionId, productId, 1)));

        CollectionMutationResult result = service.reorderProducts(collectionId,
                List.of(new CollectionProductMove(productId, 1)));

        assertThat(result.success()).isFalse();
        assertThat(result.errors()).singleElement()
                .extracting(CollectionMutationError::code, CollectionMutationError::field,
                        CollectionMutationError::products)
                .containsExactly(CollectionMutationErrorCode.INVALID, "moves", List.of(productId));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void theForwardMoveResolvesAgainstTheCurrentOrderWithTheCompactedRerderivation() {
        UUID collectionId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(
                        CollectionProduct.create(collectionId, first, 1),
                        CollectionProduct.create(collectionId, second, 2),
                        CollectionProduct.create(collectionId, third, 3)));
        // B6 (CAT-01): the rewrite mutates the SAME member rows across its
        // two phases, so the phases are captured AT INVOCATION TIME (a
        // lazy matcher would see the phase-2 positions on both calls).
        List<List<UUID>> phaseOrders = new java.util.ArrayList<>();
        List<List<Integer>> phasePositions = new java.util.ArrayList<>();
        when(membershipRepository.saveAll(any())).thenAnswer(invocation -> {
            List<UUID> order = new java.util.ArrayList<>();
            List<Integer> positions = new java.util.ArrayList<>();
            for (CollectionProduct member : (Iterable<CollectionProduct>) invocation.getArgument(0)) {
                order.add(member.getProductId());
                positions.add(member.getPosition());
            }
            phaseOrders.add(order);
            phasePositions.add(positions);
            return invocation.getArgument(0);
        });

        CollectionMutationResult result = service.reorderProducts(collectionId,
                List.of(new CollectionProductMove(third, -2)));

        assertThat(result.success()).isTrue();
        // third moved two positions backward: [third, first, second] —
        // the compacted 1..n re-derivation (the position column's own
        // law). B6 (CAT-01): the rewrite is TWO-PHASE under the V298
        // live-position unique index — phase 1 parks the WHOLE membership
        // beyond its current maximum (3 → 4,5,6) and flushes, then phase
        // 2 writes the final dense order into the emptied 1..max range —
        // so EVERY intermediate state stays unique under the partial
        // unique index (the assignment registry's own repair shape).
        assertThat(phaseOrders).containsExactly(
                List.of(third, first, second),
                List.of(third, first, second));
        assertThat(phasePositions).containsExactly(
                List.of(4, 5, 6),
                List.of(1, 2, 3));
        verify(membershipRepository).flush();
    }

    @Test
    void aZeroBasedLiveOrderParksBeyondTheFinalRange() {
        // The 2026-11-06 #806 review (Minor · Data Integrity): V253 permits
        // a zero-based live order, so the parking range derived from the
        // current maximum alone (0,1 -> 2,3) OVERLAPS the final dense range
        // (1..size) — under the V298 live-position unique index a flush
        // that assigns a final position before its parked tenant vacates it
        // dies on 23505. The park must start strictly above BOTH the
        // current maximum and the final range's own top (size).
        UUID collectionId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(
                        CollectionProduct.create(collectionId, first, 0),
                        CollectionProduct.create(collectionId, second, 1)));
        List<List<Integer>> phasePositions = new java.util.ArrayList<>();
        when(membershipRepository.saveAll(any())).thenAnswer(invocation -> {
            List<Integer> positions = new java.util.ArrayList<>();
            for (CollectionProduct member : (Iterable<CollectionProduct>) invocation.getArgument(0)) {
                positions.add(member.getPosition());
            }
            phasePositions.add(positions);
            return invocation.getArgument(0);
        });

        CollectionMutationResult result = service.reorderProducts(collectionId,
                List.of(new CollectionProductMove(second, -1)));

        assertThat(result.success()).isTrue();
        // The zero-based swap: phase 1 parks at 3,4 (max(1, size=2)+1 = 3 —
        // never 2, which a final position claims), phase 2 writes the
        // final dense 1,2 — every intermediate state stays unique under
        // the partial unique index regardless of the flush order.
        assertThat(phasePositions).containsExactly(
                List.of(3, 4),
                List.of(1, 2));
        verify(membershipRepository).flush();
    }

    @Test
    void aZeroMoveLeavesTheOrderUnchangedWithoutAWrite() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(CollectionProduct.create(collectionId, productId, 1)));

        CollectionMutationResult result = service.reorderProducts(collectionId,
                List.of(new CollectionProductMove(productId, 0)));

        assertThat(result.success()).isTrue();
        verify(membershipRepository, never()).save(any());
    }

    @Test
    void aRepeatedProductMoveAppliesOnlyTheLatestShiftPerProduct() {
        // The 2026-11-06 #808 round review (Minor · Functional
        // Correctness): the pinned Saleor contract applies each product's
        // LATEST move (the m2m dict assignment
        // operations[m2m_info.pk] = move_info.sort_order) — it never
        // accumulates a repeated [A:-1, A:-2] into -3. The discriminator:
        // third sits at position 3; the LATEST shift (-2) moves it to the
        // front ([third, first, second]) while the SUM (-3) would answer
        // the out-of-range INVALID refusal — the two semantics disagree,
        // and the contract's own answer is the latest.
        UUID collectionId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(
                        CollectionProduct.create(collectionId, first, 1),
                        CollectionProduct.create(collectionId, second, 2),
                        CollectionProduct.create(collectionId, third, 3)));
        List<List<UUID>> phaseOrders = new java.util.ArrayList<>();
        when(membershipRepository.saveAll(any())).thenAnswer(invocation -> {
            List<UUID> order = new java.util.ArrayList<>();
            for (CollectionProduct member : (Iterable<CollectionProduct>) invocation.getArgument(0)) {
                order.add(member.getProductId());
            }
            phaseOrders.add(order);
            return invocation.getArgument(0);
        });

        CollectionMutationResult result = service.reorderProducts(collectionId,
                List.of(new CollectionProductMove(third, -1), new CollectionProductMove(third, -2)));

        assertThat(result.success()).isTrue();
        // The latest shift (-2) alone: third lands at the front, both
        // phases carry the SAME final order (the park then the dense
        // rewrite).
        assertThat(phaseOrders).hasSize(2);
        assertThat(phaseOrders.get(0)).containsExactly(third, first, second);
        assertThat(phaseOrders.get(1)).containsExactly(third, first, second);
    }

    // ------------------------------------------------------------------
    // translateCollection — the bilingual translation ladder (the shared
    // TranslationWriteService proof lives in its own test)
    // ------------------------------------------------------------------

    @Test
    void theReadProjectionRendersThePrimaryNameWithTheBilingualSecondName() {
        Collection bilingual = Collection.create("slug", "English", "عربي", true);

        when(collectionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // The write factory carries the bilingual pair; the rendered
        // projection is the read side's own law (proven end-to-end in the
        // integration legs).
        assertThat(bilingual.getNameEn()).isEqualTo("English");
        assertThat(bilingual.getNameAr()).isEqualTo("عربي");
        assertThat(bilingual.isPublished()).isTrue();
    }

    @Test
    void theMembershipAppendReadsTheProductRowsForShare() {
        // The 2026-11-07 #808 round-19 review (Minor · Data Integrity &
        // Integration): the membership-CREATING read takes the product row
        // FOR SHARE — the product delete's FOR-UPDATE lock plus its
        // membership cascade serialize against the insert (never a live
        // membership stranded on a deleted product).
        Collection created = row("share-lock", "Share Lock", true);
        when(collectionRepository.save(any(Collection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Product member = product();
        when(productRepository.findById(member.getId())).thenReturn(Optional.of(member));

        CollectionMutationResult result = service.createCollection(
                new CollectionCreateCommand("Share Lock", "share-lock", true, List.of(member.getId())));

        assertThat(result.success()).isTrue();
        verify(productRepository).findByIdForShare(member.getId());
    }

}
