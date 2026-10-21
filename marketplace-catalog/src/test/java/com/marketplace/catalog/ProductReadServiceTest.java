package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductPage;
import com.marketplace.catalog.spi.ProductSort;
import com.marketplace.catalog.spi.ProductView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — packet campaign-b4-c, the
 * {@code product}/{@code products} read roots): the owning use case's
 * unit proof — the JUnit 5 + Mockito style the module's own
 * {@code AttributeReadServiceTest} established (no Spring context: the
 * {@code @Transactional} proxies are integration concerns proven by the
 * app module's real-stack tests; the business decisions the boundary
 * owns are proven HERE).
 *
 * <p>Coverage target: every method branch of {@link ProductReadService}
 * reachable through its typed seam — the PERMISSION-AWARE population
 * selection (the ADMIN capability's include-unpublished scroll and
 * count vs the public ACTIVE-scoped scroll and count, in both sort
 * directions), the lookup's readable-population boundary (null key,
 * unknown key, an ACTIVE row readable by any caller, an unpublished row
 * readable ONLY by the ADMIN capability — the data-scope negative),
 * the required-sort guard, and the bounded projection itself.</p>
 */
class ProductReadServiceTest {

    private ProductRepository repository;
    private ProductReadService service;
    private Authentication publicCaller;
    private Authentication adminCaller;

    @BeforeEach
    void setUp() {
        repository = mock(ProductRepository.class);
        service = new ProductReadService(repository);
        publicCaller = mock(Authentication.class);
        // Authentication.getAuthorities() returns Collection<? extends
        // GrantedAuthority> (the Spring Security 7.1.1 interface contract) —
        // a wildcard-capture return Mockito's when(...).thenReturn(...) cannot
        // type-check; doReturn().when(...) is Mockito's own documented form
        // for stubbing wildcard-generic returns.
        doReturn(List.<GrantedAuthority>of()).when(publicCaller).getAuthorities();
        adminCaller = mock(Authentication.class);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .when(adminCaller).getAuthorities();
    }

    private static Product activeProduct(String title) {
        // register without a goods schema: the factory's own ACTIVE path.
        return Product.register("b4c-cat", title, null, 1000L, "SAR", UUID.randomUUID());
    }

    private static Product draftProduct(String title) {
        // register WITH a goods schema: the factory's own DRAFT path
        // (the unpublished population the data-scope boundary guards).
        return Product.register("b4c-cat", title, null, 1000L, "SAR", UUID.randomUUID(), UUID.randomUUID());
    }

    // ------------------------------------------------------------------
    // findProductById — the permission-aware readable population
    // ------------------------------------------------------------------

    @Test
    void nullIdAnswersEmptyWithoutTouchingTheRepository() {
        assertThat(service.findProductById(null, publicCaller)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void unknownIdAnswersEmptyNeverAnError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());
        assertThat(service.findProductById(unknown, publicCaller)).isEmpty();
    }

    @Test
    void activeRowIsReadableByAnyAuthenticatedCaller() {
        Product product = activeProduct("Public Widget");
        when(repository.findById(product.getId())).thenReturn(Optional.of(product));

        assertThat(service.findProductById(product.getId(), publicCaller))
                .contains(new ProductView(product.getId(), "Public Widget", null, "ACTIVE"));
        assertThat(service.findProductById(product.getId(), adminCaller))
                .contains(new ProductView(product.getId(), "Public Widget", null, "ACTIVE"));
    }

    @Test
    void unpublishedRowIsReadableOnlyByTheAdminCapability() {
        // The data-scope negative: a DRAFT row answers the safe empty
        // absence for the public caller — never an error, never a leak.
        Product draft = draftProduct("Unpublished Draft");
        when(repository.findById(draft.getId())).thenReturn(Optional.of(draft));

        assertThat(service.findProductById(draft.getId(), publicCaller)).isEmpty();
        assertThat(service.findProductById(draft.getId(), adminCaller))
                .contains(new ProductView(draft.getId(), "Unpublished Draft", draft.getProductTypeId(), "DRAFT"));
    }

    @Test
    void theProjectionCarriesTheSchemaReferenceAndStatusFacts() {
        Product product = activeProduct("Typed Widget");
        Product typed = Product.register("b4c-cat", "Typed Widget", null, 1000L, "SAR",
                UUID.randomUUID(), product.getId());
        when(repository.findById(typed.getId())).thenReturn(Optional.of(typed));

        Optional<ProductView> found = service.findProductById(typed.getId(), adminCaller);
        assertThat(found).isPresent();
        assertThat(found.get().productTypeId()).isEqualTo(product.getId());
        assertThat(found.get().status()).isEqualTo("DRAFT");
        assertThat(found.get().name()).isEqualTo("Typed Widget");
    }

    // ------------------------------------------------------------------
    // scrollProducts — the permission-aware population's SQL keyset scroll
    // ------------------------------------------------------------------

    @Test
    void nullSortAnswersTheClassifiedError() {
        assertThatThrownBy(() -> service.scrollProducts(publicCaller, null,
                        ScrollPosition.keyset(), Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A product sort is required");
    }

    @Test
    void publicCallerScrollsTheActivePopulationWithItsOwnCount() {
        Window<Product> window = Window.from(List.<Product>of(), index -> ScrollPosition.keyset(), false);
        when(repository.findByStatusOrderByTitleAscIdAsc(any(StoreProductStatus.class),
                        any(KeysetScrollPosition.class), any(Limit.class)))
                .thenReturn(window);
        when(repository.countByStatus(StoreProductStatus.ACTIVE)).thenReturn(7L);

        ProductPage page = service.scrollProducts(publicCaller,
                ProductSort.of(ProductSort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.total()).isEqualTo(7L);
        verify(repository).findByStatusOrderByTitleAscIdAsc(StoreProductStatus.ACTIVE,
                ScrollPosition.keyset(), Limit.of(10));
        verify(repository).countByStatus(StoreProductStatus.ACTIVE);
        verify(repository, never()).findByOrderByTitleAscIdAsc(any(), any());
        verify(repository, never()).count();
    }

    @Test
    void adminCallerScrollsTheIncludeUnpublishedPopulationWithTheWholeCount() {
        Window<Product> window = Window.from(List.<Product>of(), index -> ScrollPosition.keyset(), false);
        when(repository.findByOrderByTitleDescIdDesc(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenReturn(window);
        when(repository.count()).thenReturn(11L);

        ProductPage page = service.scrollProducts(adminCaller,
                ProductSort.of(ProductSort.Field.NAME, false), ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.total()).isEqualTo(11L);
        verify(repository).findByOrderByTitleDescIdDesc(ScrollPosition.keyset(), Limit.of(10));
        verify(repository).count();
        verify(repository, never()).findByStatusOrderByTitleDescIdDesc(any(), any(), any());
    }

    @Test
    void theWindowMapsOntoTheBoundedProjection() {
        Product first = activeProduct("Alpha Widget");
        Product second = draftProduct("Beta Widget");
        Window<Product> window = Window.from(List.of(first, second), index ->
                        ScrollPosition.forward(java.util.Map.of("name", index == 0 ? "Alpha Widget" : "Beta Widget",
                                "id", index == 0 ? first.getId() : second.getId())),
                true);
        when(repository.findByOrderByTitleAscIdAsc(any(KeysetScrollPosition.class), any(Limit.class)))
                .thenReturn(window);
        when(repository.count()).thenReturn(2L);

        ProductPage page = service.scrollProducts(adminCaller,
                ProductSort.of(ProductSort.Field.NAME, true), ScrollPosition.keyset(), Limit.of(10));

        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.window().getContent().get(0))
                .isEqualTo(new ProductView(first.getId(), "Alpha Widget", null, "ACTIVE"));
        assertThat(page.window().getContent().get(1))
                .isEqualTo(new ProductView(second.getId(), "Beta Widget", second.getProductTypeId(), "DRAFT"));
        assertThat(page.window().hasNext()).isTrue();
    }
}
