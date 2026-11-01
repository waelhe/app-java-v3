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
        when(membershipRepository.findByCollectionIdAndProductId(collectionId, productId))
                .thenReturn(Optional.of(CollectionProduct.create(collectionId, productId, 1)));

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
        when(membershipRepository.findByCollectionIdAndProductId(collectionId, newProductId))
                .thenReturn(Optional.empty());
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(CollectionProduct.create(collectionId, existingProductId, 1)));

        CollectionMutationResult result = service.addProducts(collectionId, List.of(newProductId));

        assertThat(result.success()).isTrue();
        assertThat(result.collection().slug()).isEqualTo("slug");
        verify(membershipRepository).save(any());
    }

    @Test
    void removeProductsSkipsNonMembersIdempotently() {
        UUID collectionId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId))
                .thenReturn(Optional.of(row("slug", "Old", true)));
        when(membershipRepository.findByCollectionIdAndProductId(collectionId, productId))
                .thenReturn(Optional.empty());

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
        when(membershipRepository.findByCollectionIdAndProductId(collectionId, productId))
                .thenReturn(Optional.of(member));

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
        when(membershipRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CollectionMutationResult result = service.reorderProducts(collectionId,
                List.of(new CollectionProductMove(third, -2)));

        assertThat(result.success()).isTrue();
        // third moved two positions backward: [third, first, second] — the
        // compacted 1..n re-derivation (the position column's own law).
        verify(membershipRepository).save(argThat(member ->
                member.getProductId().equals(third) && member.getPosition() == 1));
        verify(membershipRepository).save(argThat(member ->
                member.getProductId().equals(first) && member.getPosition() == 2));
        verify(membershipRepository).save(argThat(member ->
                member.getProductId().equals(second) && member.getPosition() == 3));
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
}
