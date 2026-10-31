package com.marketplace.catalog;

import com.marketplace.catalog.spi.CollectionPage;
import com.marketplace.catalog.spi.CollectionProductsPage;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionSort;
import com.marketplace.catalog.spi.CollectionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collection}
 * and {@code collections} read roots and the {@code Collection.products}
 * field, packet agent-2): the owning read use case's unit proof — the
 * JUnit 5 + Mockito style the module's own {@code CategoryReadServiceTest}
 * established (no Spring context: the publication law rides the received
 * {@code Authentication}, proven HERE over the testing token).
 *
 * <p>Coverage target: the publication law (the ADMIN-class caller sees
 * every live row; any other authenticated caller sees the published rows
 * alone; an unpublished row's lookup by a non-ADMIN caller answers the
 * same safe absence as a missing one), the keyset scroll's deterministic
 * NAME ordering with the id tiebreaker and its forward-keyset cursor
 * contract (the malformed-position refusal), the member-products
 * scroll's (position, id) ordering with the same laws, and the
 * member-product publication law (the non-ADMIN scroll carries the
 * ACTIVE products alone and the total answers the same visibility).</p>
 */
class CollectionReadServiceTest {

    private CollectionRepository collectionRepository;
    private CollectionProductRepository membershipRepository;
    private ProductRepository productRepository;
    private CollectionReadSpi service;

    /** The ADMIN-class caller — the include-unpublished capability. */
    private final Authentication admin =
            new TestingAuthenticationToken("admin-subject", "n/a", "ROLE_ADMIN");

    /** The plain authenticated caller — the published-only visibility. */
    private final Authentication member =
            new TestingAuthenticationToken("member-subject", "n/a", "ROLE_CONSUMER");

    @BeforeEach
    void setUp() {
        collectionRepository = mock(CollectionRepository.class);
        membershipRepository = mock(CollectionProductRepository.class);
        productRepository = mock(ProductRepository.class);
        service = new CollectionReadService(
                collectionRepository, membershipRepository, productRepository);
    }

    private static Collection publishedRow(String slug, String nameEn) {
        return Collection.create(slug, nameEn, null, true);
    }

    private static Collection unpublishedRow(String slug, String nameEn) {
        return Collection.create(slug, nameEn, null, false);
    }

    private static Product activeProduct(UUID id) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        when(product.getTitle()).thenReturn("Product " + id);
        when(product.getProductTypeId()).thenReturn(null);
        when(product.getStatus()).thenReturn(StoreProductStatus.ACTIVE);
        return product;
    }

    private static Product draftProduct(UUID id) {
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(id);
        when(product.getTitle()).thenReturn("Draft " + id);
        when(product.getProductTypeId()).thenReturn(null);
        when(product.getStatus()).thenReturn(StoreProductStatus.DRAFT);
        return product;
    }

    // ------------------------------------------------------------------
    // findCollectionById / findCollectionBySlug — the publication law
    // ------------------------------------------------------------------

    @Test
    void theAdminCallerSeesTheUnpublishedRow() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(
                Optional.of(unpublishedRow("slug", "Hidden")));

        Optional<CollectionView> view = service.findCollectionById(id, admin);

        assertThat(view).isPresent();
        assertThat(view.get().published()).isFalse();
    }

    @Test
    void thePlainCallerSeesOnlyThePublishedRow() {
        UUID id = UUID.randomUUID();
        when(collectionRepository.findById(id)).thenReturn(
                Optional.of(unpublishedRow("slug", "Hidden")));

        assertThat(service.findCollectionById(id, member)).isEmpty();
    }

    @Test
    void anUnknownButWellFormedIdAnswersTheSafeEmpty() {
        assertThat(service.findCollectionById(UUID.randomUUID(), admin)).isEmpty();
        assertThat(service.findCollectionById(null, admin)).isEmpty();
    }

    @Test
    void theSlugLookupCarriesTheSamePublicationLaw() {
        when(collectionRepository.findBySlug("published")).thenReturn(
                Optional.of(publishedRow("published", "Public")));
        when(collectionRepository.findBySlug("unpublished")).thenReturn(
                Optional.of(unpublishedRow("unpublished", "Hidden")));

        assertThat(service.findCollectionBySlug("published", member)).isPresent();
        assertThat(service.findCollectionBySlug("unpublished", member)).isEmpty();
        assertThat(service.findCollectionBySlug("unpublished", admin)).isPresent();
        assertThat(service.findCollectionBySlug("  ", admin)).isEmpty();
    }

    // ------------------------------------------------------------------
    // scrollCollections — the NAME keyset scroll
    // ------------------------------------------------------------------

    @Test
    void theScrollOrdersByNameWithTheIdTiebreakerAndAnswersTheVisibleTotal() {
        when(collectionRepository.findAll()).thenReturn(List.of(
                publishedRow("b", "Bravo"),
                unpublishedRow("c", "Charlie"),
                publishedRow("a", "Alpha")));

        CollectionPage page = service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.total()).isEqualTo(3);
        List<CollectionView> content = page.window().getContent();
        assertThat(content).extracting(CollectionView::name)
                .containsExactly("Alpha", "Bravo", "Charlie");
        assertThat(page.window().hasNext()).isFalse();
    }

    @Test
    void thePlainCallersScrollCarriesThePublishedRowsAlone() {
        when(collectionRepository.findAll()).thenReturn(List.of(
                publishedRow("a", "Alpha"),
                unpublishedRow("b", "Bravo")));

        CollectionPage page = service.scrollCollections(member,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.window().getContent()).extracting(CollectionView::name)
                .containsExactly("Alpha");
    }

    @Test
    void theDescendingScrollReversesTheOrder() {
        when(collectionRepository.findAll()).thenReturn(List.of(
                publishedRow("a", "Alpha"),
                publishedRow("b", "Bravo")));

        CollectionPage page = service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, false),
                ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.window().getContent()).extracting(CollectionView::name)
                .containsExactly("Bravo", "Alpha");
    }

    @Test
    void thePageSizeLimitDrivesHasNextAndTheWindow() {
        when(collectionRepository.findAll()).thenReturn(List.of(
                publishedRow("a", "Alpha"),
                publishedRow("b", "Bravo"),
                publishedRow("c", "Charlie")));

        CollectionPage page = service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                ScrollPosition.keyset(), Limit.of(2));

        assertThat(page.window().hasNext()).isTrue();
        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void aNullSortAnswersTheClassifiedValidationError() {
        assertThatThrownBy(() -> service.scrollCollections(admin, null,
                ScrollPosition.keyset(), Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNonForwardKeysetPositionAnswersTheClassifiedValidationError() {
        when(collectionRepository.findAll()).thenReturn(List.of(publishedRow("a", "Alpha")));

        KeysetScrollPosition backward = (KeysetScrollPosition) ScrollPosition.backward(
                Map.of("name", "Alpha", "id", UUID.randomUUID()));
        assertThatThrownBy(() -> service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                backward, Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset");
    }

    @Test
    void theForwardKeysetFollowsTheCursorsOwnSortKey() {
        Collection first = publishedRow("a", "Alpha");
        Collection second = publishedRow("b", "Bravo");
        when(collectionRepository.findAll()).thenReturn(List.of(second, first));

        KeysetScrollPosition afterFirst = ScrollPosition.forward(Map.of(
                "name", "Alpha", "id", first.getId()));

        CollectionPage page = service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                afterFirst, Limit.of(10));

        // The page follows the cursor's own (name, id) key strictly — the
        // elements AFTER Alpha in the ascending order.
        assertThat(page.window().getContent()).extracting(CollectionView::name)
                .containsExactly("Bravo");
    }

    // ------------------------------------------------------------------
    // scrollCollectionProducts — the membership's own (position, id) scroll
    // ------------------------------------------------------------------

    @Test
    void theMemberProductsScrollCarriesTheMembershipOrderAndTheVisibleTotal() {
        UUID collectionId = UUID.randomUUID();
        UUID productIdA = UUID.randomUUID();
        UUID productIdB = UUID.randomUUID();
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(
                        CollectionProduct.create(collectionId, productIdB, 2),
                        CollectionProduct.create(collectionId, productIdA, 1)));
        Product productA = activeProduct(productIdA);
        Product productB = activeProduct(productIdB);
        when(productRepository.findById(productIdA)).thenReturn(Optional.of(productA));
        when(productRepository.findById(productIdB)).thenReturn(Optional.of(productB));

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), admin);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.window().getContent()).extracting(product -> product.id())
                .containsExactly(productIdA, productIdB);
    }

    @Test
    void thePlainCallersMemberProductsScrollCarriesTheActiveProductsAlone() {
        UUID collectionId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(
                        CollectionProduct.create(collectionId, activeId, 1),
                        CollectionProduct.create(collectionId, draftId, 2)));
        Product active = activeProduct(activeId);
        Product draft = draftProduct(draftId);
        when(productRepository.findById(activeId)).thenReturn(Optional.of(active));
        when(productRepository.findById(draftId)).thenReturn(Optional.of(draft));

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), member);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.window().getContent()).extracting(product -> product.id())
                .containsExactly(activeId);
    }

    @Test
    void theAdminCallersMemberProductsScrollCarriesTheDraftProductsToo() {
        UUID collectionId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(CollectionProduct.create(collectionId, draftId, 1)));
        Product draft = draftProduct(draftId);
        when(productRepository.findById(draftId)).thenReturn(Optional.of(draft));

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), admin);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.window().getContent()).extracting(product -> product.id())
                .containsExactly(draftId);
    }

    @Test
    void theMemberProductsScrollFollowsTheCursorsOwnPositionKey() {
        UUID collectionId = UUID.randomUUID();
        UUID productIdA = UUID.randomUUID();
        UUID productIdB = UUID.randomUUID();
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of(
                        CollectionProduct.create(collectionId, productIdA, 1),
                        CollectionProduct.create(collectionId, productIdB, 2)));
        Product productA = activeProduct(productIdA);
        Product productB = activeProduct(productIdB);
        when(productRepository.findById(productIdA)).thenReturn(Optional.of(productA));
        when(productRepository.findById(productIdB)).thenReturn(Optional.of(productB));

        KeysetScrollPosition afterFirst = ScrollPosition.forward(Map.of(
                "position", 1, "id", productIdA));

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, afterFirst, Limit.of(10), admin);

        assertThat(page.window().getContent()).extracting(product -> product.id())
                .containsExactly(productIdB);
    }

    @Test
    void aNullCollectionIdAnswersTheClassifiedValidationError() {
        assertThatThrownBy(() -> service.scrollCollectionProducts(null,
                ScrollPosition.keyset(), Limit.of(10), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collection id");
    }

    @Test
    void aMalformedPositionKeyAnswersTheClassifiedValidationError() {
        UUID collectionId = UUID.randomUUID();
        when(membershipRepository.findByCollectionIdOrderByPositionAscIdAsc(collectionId))
                .thenReturn(List.of());

        KeysetScrollPosition malformed = ScrollPosition.forward(Map.of(
                "position", "not-a-number", "id", UUID.randomUUID()));

        assertThatThrownBy(() -> service.scrollCollectionProducts(
                collectionId, malformed, Limit.of(10), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parseable position");
    }

    // ------------------------------------------------------------------
    // the bounded projection's own rendering law
    // ------------------------------------------------------------------

    @Test
    void theProjectionRendersThePrimaryNameAndThePublicationFact() {
        when(collectionRepository.findAll()).thenReturn(List.of(
                Collection.create("slug", "English", "عربي", true)));

        CollectionPage page = service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                ScrollPosition.keyset(), Limit.of(10));

        CollectionView view = page.window().getContent().get(0);
        assertThat(view.name()).isEqualTo("English");
        assertThat(view.nameAr()).isEqualTo("عربي");
        assertThat(view.slug()).isEqualTo("slug");
        assertThat(view.published()).isTrue();
    }

    @Test
    void aRowCarryingNeitherNameOrdersByTheEmptyKeyAndAnswersTheNullWireName() {
        when(collectionRepository.findAll()).thenReturn(List.of(
                Collection.create("a", null, null, true),
                publishedRow("b", "Bravo")));

        CollectionPage page = service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                ScrollPosition.keyset(), Limit.of(10));

        // Ascending orders the empty-key rows first (the total-ordering
        // key's own law); the wire name stays the honest null.
        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.window().getContent().get(0).name()).isNull();
        assertThat(page.window().getContent().get(0).slug()).isEqualTo("a");
    }
}
