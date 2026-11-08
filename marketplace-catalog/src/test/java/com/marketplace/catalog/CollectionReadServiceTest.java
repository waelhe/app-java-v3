package com.marketplace.catalog;

import com.marketplace.catalog.spi.CollectionPage;
import com.marketplace.catalog.spi.CollectionProductsPage;
import com.marketplace.catalog.spi.CollectionReadSpi;
import com.marketplace.catalog.spi.CollectionSort;
import com.marketplace.catalog.spi.CollectionView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        // B8 (CAT-03): the owning use case no longer holds the products
        // port at all — the bounded joined page lives in the membership
        // registry's own repository seam, so the N×findById shape is
        // structurally gone, not merely unexercised.
        service = new CollectionReadService(collectionRepository, membershipRepository);
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

    /** The joined page row the bounded SQL answers — {@code [position, product]}. */
    private static Object[] row(int position, Product product) {
        return new Object[]{position, product};
    }

    /**
     * The typed page builder — {@code List.of(row(...), ...)} on its own
     * infers {@code List<Object>} (each {@code Object[]} argument is
     * assignable to the loose bound), which cannot stub the repository's
     * {@code List<Object[]>} seam; the explicit varargs collector pins the
     * element type (the CI compile round's own measured evidence).
     */
    @SafeVarargs
    private static List<Object[]> joinedPage(Object[]... orderedRows) {
        return List.of(orderedRows);
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
        // The joined page the bounded SQL answers — (position, product)
        // rows already in the membership's own (position, id) order (the
        // ORDER BY owns the ordering now; the in-memory sort is gone).
        // The mocked member products are built BEFORE the stubbing: a mock
        // created (and stubbed) inside thenReturn(...) clobbers the outer
        // when() — Mockito's documented unfinished-stubbing race, the exact
        // failure the CI round caught here.
        Product rowA = activeProduct(productIdA);
        Product rowB = activeProduct(productIdB);
        when(membershipRepository.findFirstMembershipProductRows(
                eq(collectionId), eq(true), any(Pageable.class)))
                .thenReturn(joinedPage(
                        row(1, rowA),
                        row(2, rowB)));
        when(membershipRepository.countVisibleMembershipProducts(collectionId, true)).thenReturn(2L);

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), admin);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.window().getContent()).extracting(product -> product.id())
                .containsExactly(productIdA, productIdB);
        assertThat(page.window().hasNext()).isFalse();
        // CAT-03's own structural pin: the bounded page NEVER rides the
        // unbounded whole-membership seam.
        verify(membershipRepository, never()).findByCollectionIdOrderByPositionAscIdAsc(any());
    }

    @Test
    void thePlainCallersMemberProductsScrollCarriesTheActiveProductsAlone() {
        UUID collectionId = UUID.randomUUID();
        UUID activeId = UUID.randomUUID();
        // The wave-19 #808 review's gate: the member-products scroll reads
        // the collection's own publication fact FIRST (a live stub for the
        // published collection the caller may read).
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(
                Collection.create("member-slug", "Members", "الأعضاء", true)));
        // The PUBLIC page the joined SQL answers: the ACTIVE member alone
        // — the joined publication law filters the draft member inside
        // the SAME statement, so a run of draft members can never shrink
        // a public caller's page (the page math stays honest).
        Product theActiveMember = activeProduct(activeId);
        when(membershipRepository.findFirstMembershipProductRows(
                eq(collectionId), eq(false), any(Pageable.class)))
                .thenReturn(joinedPage(row(1, theActiveMember)));
        when(membershipRepository.countVisibleMembershipProducts(collectionId, false)).thenReturn(1L);

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), member);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.window().getContent()).extracting(product -> product.id())
                .containsExactly(activeId);
    }

    @Test
    void thePlainCallersScrollOverAnUnpublishedCollectionAnswersTheSafeAbsence() {
        // The wave-19 #808 review (Major · Security & Privacy): the
        // collection's publication fact gates the member-products scroll —
        // a non-ADMIN caller's scroll over an unpublished collection
        // answers the same safe absence as the lookup (never a product
        // exposure through a collection the same caller cannot read), and
        // the membership registry is never consulted.
        UUID collectionId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.of(
                Collection.create("hidden-slug", "Hidden", "المخفية", false)));

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), member);

        assertThat(page.total()).isZero();
        assertThat(page.window().getContent()).isEmpty();
        verify(membershipRepository, never()).findByCollectionIdOrderByPositionAscIdAsc(any());
        verify(membershipRepository, never()).findFirstMembershipProductRows(any(), anyBoolean(), any());
    }

    @Test
    void thePlainCallersScrollOverAnUnknownCollectionAnswersTheSafeAbsence() {
        // The gate's unknown-id shape: the same empty page an unknown but
        // well-formed collection lookup answers — never an existence
        // disclosure through the products scroll.
        UUID collectionId = UUID.randomUUID();
        when(collectionRepository.findById(collectionId)).thenReturn(Optional.empty());

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), member);

        assertThat(page.total()).isZero();
        assertThat(page.window().getContent()).isEmpty();
        verify(membershipRepository, never()).findByCollectionIdOrderByPositionAscIdAsc(any());
        verify(membershipRepository, never()).findFirstMembershipProductRows(any(), anyBoolean(), any());
    }

    @Test
    void theAdminCallersMemberProductsScrollCarriesTheDraftProductsToo() {
        UUID collectionId = UUID.randomUUID();
        UUID draftId = UUID.randomUUID();
        Product theDraftMember = draftProduct(draftId);
        when(membershipRepository.findFirstMembershipProductRows(
                eq(collectionId), eq(true), any(Pageable.class)))
                .thenReturn(joinedPage(row(1, theDraftMember)));
        when(membershipRepository.countVisibleMembershipProducts(collectionId, true)).thenReturn(1L);

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
        KeysetScrollPosition afterFirst = ScrollPosition.forward(Map.of(
                "position", 1, "id", productIdA));
        Product rowB = activeProduct(productIdB);
        when(membershipRepository.findNextMembershipProductRows(
                eq(collectionId), eq(true), eq(1), eq(productIdA), any(Pageable.class)))
                .thenReturn(joinedPage(row(2, rowB)));
        when(membershipRepository.countVisibleMembershipProducts(collectionId, true)).thenReturn(2L);

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

        KeysetScrollPosition malformed = ScrollPosition.forward(Map.of(
                "position", "not-a-number", "id", UUID.randomUUID()));

        assertThatThrownBy(() -> service.scrollCollectionProducts(
                collectionId, malformed, Limit.of(10), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parseable position");
    }

    // ------------------------------------------------------------------
    // B8 (CAT-03) — the bounded page's own laws
    // ------------------------------------------------------------------

    @Test
    void thePageSizeLimitDrivesHasNextAndNeverLeaksTheProbeRow() {
        UUID collectionId = UUID.randomUUID();
        // The page ask is 10; the SQL returns 11 — the one-beyond probe
        // row that answers hasNext inside the same statement.
        List<Object[]> rows = new ArrayList<>();
        for (int position = 1; position <= 11; position++) {
            rows.add(row(position, activeProduct(UUID.randomUUID())));
        }
        when(membershipRepository.findFirstMembershipProductRows(
                eq(collectionId), eq(true), any(Pageable.class)))
                .thenReturn(rows);
        when(membershipRepository.countVisibleMembershipProducts(collectionId, true)).thenReturn(11L);

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.of(10), admin);

        assertThat(page.window().hasNext()).isTrue();
        assertThat(page.window().getContent()).hasSize(10);
        assertThat(page.total()).isEqualTo(11);
    }

    @Test
    void anUnlimitedRequestAnswersTheBoundedDefaultPageNeverTheWholeMembership() {
        UUID collectionId = UUID.randomUUID();
        Product theMember = activeProduct(UUID.randomUUID());
        when(membershipRepository.findFirstMembershipProductRows(
                eq(collectionId), eq(true), any(Pageable.class)))
                .thenReturn(joinedPage(row(1, theMember)));
        when(membershipRepository.countVisibleMembershipProducts(collectionId, true)).thenReturn(500L);

        CollectionProductsPage page = service.scrollCollectionProducts(
                collectionId, ScrollPosition.keyset(), Limit.unlimited(), admin);

        // The CAT-03 pin: the unlimited seam answers the platform's
        // documented bounded ceiling (100 + the one probe row = a 101-row
        // ask), never the load of the whole 500-member registry.
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(membershipRepository).findFirstMembershipProductRows(
                eq(collectionId), eq(true), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(101);
        assertThat(page.total()).isEqualTo(500);
    }

    @Test
    void aNonPositivePageSizeAnswersTheClassifiedValidationError() {
        // The sibling ProductReadService guard's own contract — an
        // unbounded page would replace the SQL keyset with an unbounded
        // load (the exact CAT-03 shape), so the honest classified error.
        assertThatThrownBy(() -> service.scrollCollectionProducts(UUID.randomUUID(),
                ScrollPosition.keyset(), Limit.of(0), admin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive page size");
    }

    @Test
    void theCollectionsScrollRejectsANonPositiveBoundedPageSize() {
        // The 2026-11-08 #808 round-24 review (the body's unverified
        // finding, verified against the code — the member scroll and
        // scrollVariants guards' own law): a BOUNDED zero page size cut
        // an EMPTY window that still advertised hasNext=true with no
        // element from which to obtain a continuation cursor (the
        // client either stopped prematurely or repeated the same empty
        // page forever). The registry scroll now refuses it up front
        // with the same classified error.
        assertThatThrownBy(() -> service.scrollCollections(admin,
                CollectionSort.of(CollectionSort.Field.NAME, true),
                ScrollPosition.keyset(), Limit.of(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive page size");
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
