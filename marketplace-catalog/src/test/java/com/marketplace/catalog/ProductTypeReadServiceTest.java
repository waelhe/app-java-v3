package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductTypePage;
import com.marketplace.catalog.spi.ProductTypeSort;
import com.marketplace.catalog.spi.ProductTypeView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — the campaign-b2-a2 product-type read
 * roots): the owning use case's unit proof — the JUnit 5 + Mockito style the
 * module's own {@code ProductTypeServiceTest}/{@code CatalogServiceTest}
 * established (no Spring context: the {@code @PreAuthorize}/
 * {@code @Transactional} proxies are integration concerns proven by the app
 * module's real-stack tests; the business decisions the boundary owns are
 * proven HERE).
 *
 * <p>Coverage target: every method branch of {@link ProductTypeReadService}
 * reachable through its typed seam — the id lookup (null key, unknown key,
 * found, and the bilingual {@code nameEn ?? nameAr} rendering), and the
 * in-memory keyset scroll in BOTH directions (deterministic with the
 * {@code id} tiebreaker), the keyset predicate (cursor follow-through in
 * both directions, the string-form {@code id} parsing leg, the
 * malformed-cursor rejections: a backward keyset, a cursor keyed by
 * something other than the {@code name} property, a missing {@code id} key,
 * an unparseable {@code id} value), the limit legs (absent limit, an
 * under-collection limit with {@code hasNext}, an over-collection limit),
 * the empty collection, and the {@code totalCount} fact (the full
 * collection's size, never the page's).</p>
 */
class ProductTypeReadServiceTest {

    private ProductTypeRepository repository;
    private ProductTypeReadService service;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        repository = mock(ProductTypeRepository.class);
        service = new ProductTypeReadService(repository);
        authentication = mock(Authentication.class);
    }

    private static ProductType type(String code, String nameEn, String nameAr) {
        return ProductType.create(code, nameEn, nameAr, null);
    }

    // ------------------------------------------------------------------
    // findProductTypeById — the lookup legs
    // ------------------------------------------------------------------

    @Test
    void nullIdAnswersEmptyWithoutTouchingTheRepository() {
        assertThat(service.findProductTypeById(null, authentication)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void unknownButWellFormedIdAnswersEmpty() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());
        assertThat(service.findProductTypeById(missing, authentication)).isEmpty();
    }

    @Test
    void foundIdAnswersTheBoundedProjectionWithTheEnglishPrimaryName() {
        ProductType existing = type("home-appliances", "Home appliances", "الأجهزة المنزلية");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        Optional<ProductTypeView> view = service.findProductTypeById(existing.getId(), authentication);

        assertThat(view).isPresent();
        assertThat(view.get().id()).isEqualTo(existing.getId());
        assertThat(view.get().name()).isEqualTo("Home appliances");
        assertThat(view.get().slug()).isEqualTo("home-appliances");
    }

    @Test
    void foundIdAnswersTheArabicNameWhenTheEnglishNameIsAbsent() {
        // The bilingual row's own at-least-one guarantee: nameEn null renders
        // nameAr — the registered translation-surface divergence's model.
        ProductType existing = type("kitchen", null, "المطبخ");
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        Optional<ProductTypeView> view = service.findProductTypeById(existing.getId(), authentication);

        assertThat(view).isPresent();
        assertThat(view.get().name()).isEqualTo("المطبخ");
        assertThat(view.get().slug()).isEqualTo("kitchen");
    }

    // ------------------------------------------------------------------
    // scrollProductTypes — the sort validation legs
    // ------------------------------------------------------------------

    @Test
    void missingSortAnswersTheClassifiedValidationError() {
        assertThatThrownBy(() -> service.scrollProductTypes(authentication, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A product-type sort is required");
        assertThatThrownBy(() -> service.scrollProductTypes(authentication,
                        new ProductTypeSort(null, true), null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A product-type sort is required");
    }

    @Test
    void sortFactoryRejectsANullField() {
        assertThatThrownBy(() -> ProductTypeSort.of(null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A product-type sort field is required");
    }

    // ------------------------------------------------------------------
    // scrollProductTypes — the deterministic ordering legs
    // ------------------------------------------------------------------

    @Test
    void nameAscendingScrollOrdersByRenderedNameThenId() {
        ProductType apparel = type("apparel", "Apparel", "ملابس");
        ProductType appliances = type("home-appliances", "Home appliances", "الأجهزة المنزلية");
        when(repository.findAll()).thenReturn(List.of(appliances, apparel));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(ProductTypeView::name)
                .containsExactly("Apparel", "Home appliances");
        assertThat(page.window().getContent()).extracting(ProductTypeView::slug)
                .containsExactly("apparel", "home-appliances");
    }

    @Test
    void nameDescendingScrollReversesTheWindow() {
        ProductType apparel = type("apparel", "Apparel", "ملابس");
        ProductType appliances = type("home-appliances", "Home appliances", "الأجهزة المنزلية");
        when(repository.findAll()).thenReturn(List.of(apparel, appliances));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, false), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(ProductTypeView::name)
                .containsExactly("Home appliances", "Apparel");
    }

    @Test
    void equalNamesFallThroughToTheDeterministicIdTiebreaker() {
        // Two rows sharing the SAME rendered name: the smaller id leads the
        // ascending window — the deterministic ordering the SQL keyset
        // methods' own ...AscIdAsc shape guarantees.
        ProductType first = type("tie-one", "Tie", null);
        ProductType second = type("tie-two", "Tie", null);
        when(repository.findAll()).thenReturn(List.of(second, first));
        UUID expectedLeading = first.getId().compareTo(second.getId()) < 0
                ? first.getId() : second.getId();

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).extracting(ProductTypeView::id)
                .containsExactly(expectedLeading,
                        expectedLeading.equals(first.getId()) ? second.getId() : first.getId());
        assertThat(page.window().getContent())
                .isSortedAccordingTo(Comparator.comparing(ProductTypeView::id));
    }

    // ------------------------------------------------------------------
    // scrollProductTypes — the keyset predicate legs
    // ------------------------------------------------------------------

    @Test
    void cursorFollowThroughAnswersOnlyElementsAfterTheCursorSortKey() {
        ProductType alpha = type("p2t-alpha", "Alpha", null);
        ProductType beta = type("p2t-beta", "Beta", null);
        ProductType gamma = type("p2t-gamma", "Gamma", null);
        when(repository.findAll()).thenReturn(List.of(gamma, alpha, beta));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true),
                ScrollPosition.forward(Map.of("name", "Beta", "id", beta.getId())),
                null);

        assertThat(page.window().getContent()).extracting(ProductTypeView::name)
                .containsExactly("Gamma");
        // totalCount is the COLLECTION count, never the remaining page's.
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void cursorIdCarriedAsItsStringFormStillParses() {
        ProductType alpha = type("p2t-alpha", "Alpha", null);
        ProductType beta = type("p2t-beta", "Beta", null);
        when(repository.findAll()).thenReturn(List.of(alpha, beta));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true),
                ScrollPosition.forward(Map.of("name", "Alpha", "id", alpha.getId().toString())),
                null);

        assertThat(page.window().getContent()).extracting(ProductTypeView::name)
                .containsExactly("Beta");
    }

    @Test
    void keysetPredicateHonorsTheDescendingDirection() {
        ProductType alpha = type("p2t-alpha", "Alpha", null);
        ProductType beta = type("p2t-beta", "Beta", null);
        ProductType gamma = type("p2t-gamma", "Gamma", null);
        when(repository.findAll()).thenReturn(List.of(alpha, beta, gamma));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, false),
                ScrollPosition.forward(Map.of("name", "Gamma", "id", gamma.getId())),
                null);

        assertThat(page.window().getContent()).extracting(ProductTypeView::name)
                .containsExactly("Beta", "Alpha");
    }

    @Test
    void cursorKeyedByAnotherPropertyIsRejected() {
        // A cursor keyed by anything other than (name, id): the name key is
        // absent, so the honest classified error answers — never a silent
        // full re-read.
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollProductTypes(authentication,
                        ProductTypeSort.of(ProductTypeSort.Field.NAME, true),
                        ScrollPosition.forward(Map.of("slug", "p2t-alpha", "id", UUID.randomUUID())),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void cursorMissingTheIdTiebreakerKeyIsRejected() {
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollProductTypes(authentication,
                        ProductTypeSort.of(ProductTypeSort.Field.NAME, true),
                        ScrollPosition.forward(Map.of("name", "Alpha")),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void backwardKeysetScrollPositionIsRejected() {
        // The forward-only contract's own guard: a backward keyset carries
        // the right key names but the wrong direction — the honest
        // classified error answers.
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollProductTypes(authentication,
                        ProductTypeSort.of(ProductTypeSort.Field.NAME, true),
                        ScrollPosition.backward(Map.of("name", "Alpha", "id", UUID.randomUUID())),
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forward keyset over (name, id)");
    }

    @Test
    void malformedCursorIdValueAnswersTheValidationTaxonomy() {
        when(repository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.scrollProductTypes(authentication,
                        ProductTypeSort.of(ProductTypeSort.Field.NAME, true),
                        ScrollPosition.forward(Map.of("name", "Alpha", "id", "not-a-uuid")),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // scrollProductTypes — the limit, hasNext and totalCount legs
    // ------------------------------------------------------------------

    @Test
    void absentLimitAnswersTheWholeBoundedCollection() {
        ProductType alpha = type("p2t-alpha", "Alpha", null);
        ProductType beta = type("p2t-beta", "Beta", null);
        when(repository.findAll()).thenReturn(List.of(alpha, beta));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true), ScrollPosition.keyset(), null);

        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void boundedLimitAnswersThePrefixWindowWithAnHonestHasNext() {
        ProductType alpha = type("p2t-alpha", "Alpha", null);
        ProductType beta = type("p2t-beta", "Beta", null);
        ProductType gamma = type("p2t-gamma", "Gamma", null);
        when(repository.findAll()).thenReturn(List.of(gamma, alpha, beta));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true), ScrollPosition.keyset(),
                Limit.of(2));

        Window<ProductTypeView> window = page.window();
        assertThat(window.getContent()).extracting(ProductTypeView::name)
                .containsExactly("Alpha", "Beta");
        assertThat(window.hasNext()).isTrue();
        assertThat(page.total()).isEqualTo(3);

        // The emitted positions carry each row's own (name, id) sort key —
        // the keyset the opaque cursor identifies.
        KeysetScrollPosition firstPosition = (KeysetScrollPosition) window.positionAt(0);
        assertThat(firstPosition.getKeys().get("name")).isEqualTo("Alpha");
        assertThat(firstPosition.getKeys().get("id")).isEqualTo(alpha.getId());
        KeysetScrollPosition secondPosition = (KeysetScrollPosition) window.positionAt(1);
        assertThat(secondPosition.getKeys().get("name")).isEqualTo("Beta");
        assertThat(secondPosition.getKeys().get("id")).isEqualTo(beta.getId());
    }

    @Test
    void overCollectionLimitAnswersTheWholeWindow() {
        ProductType alpha = type("p2t-alpha", "Alpha", null);
        when(repository.findAll()).thenReturn(List.of(alpha));

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true), ScrollPosition.keyset(),
                Limit.of(10));

        assertThat(page.window().getContent()).hasSize(1);
        assertThat(page.window().hasNext()).isFalse();
    }

    @Test
    void emptyCollectionAnswersTheEmptyWindowWithAnHonestZeroTotal() {
        when(repository.findAll()).thenReturn(List.of());

        ProductTypePage page = service.scrollProductTypes(authentication,
                ProductTypeSort.of(ProductTypeSort.Field.NAME, true), ScrollPosition.keyset(),
                Limit.of(5));

        assertThat(page.window().getContent()).isEmpty();
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.total()).isZero();
    }

    @Test
    void lookupLegNeverConsultsTheWholeCollectionSeam() {
        // The single-row lookup reads by id only — never the whole-table
        // seam the scroll owns.
        ProductType existing = type("apparel", "Apparel", null);
        when(repository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.findProductTypeById(existing.getId(), authentication);

        verify(repository, never()).findAll();
    }
}
