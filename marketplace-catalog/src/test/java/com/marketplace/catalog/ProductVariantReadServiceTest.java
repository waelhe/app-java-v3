package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductVariantPage;
import com.marketplace.catalog.spi.ProductVariantSort;
import com.marketplace.catalog.spi.ProductVariantView;
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

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — packet campaign-b5-a, the
 * {@code productVariant}/{@code productVariants} read roots): the owning
 * use case's unit proof — the JUnit 5 + Mockito style the module's own
 * {@code ProductReadServiceTest} established (no Spring context: the
 * {@code @Transactional} proxies are integration concerns proven by the
 * app module's real-stack tests; the business decisions the boundary
 * owns are proven HERE).
 *
 * <p>Coverage target: every method branch of
 * {@link ProductVariantReadService} reachable through its typed seam —
 * the PERMISSION-AWARE population selection (the ADMIN capability's
 * include-unpublished derived scroll and count vs the public
 * two-row-gated native scroll and count, both sort fields, both
 * directions), the public scroll's own keyset binds (the
 * {@code after}-cursor's updatedAt/id values reaching the native
 * methods, the pageSize+1 has-next fetch), the lookup's
 * readable-population boundary (null key, unknown key, the ACTIVE row
 * readable by any caller, the non-ACTIVE variant AND the
 * ACTIVE-variant-under-non-ACTIVE-parent both readable ONLY by the
 * ADMIN capability — the two-row data-scope negative), the
 * required-sort/positive-page guards, and the bounded projection
 * itself (the parent projection riding the view).</p>
 */
class ProductVariantReadServiceTest {

    private ProductVariantRepository variantRepository;
    private ProductRepository productRepository;
    private ProductVariantReadService service;
    private Authentication publicCaller;
    private Authentication adminCaller;

    @BeforeEach
    void setUp() {
        variantRepository = mock(ProductVariantRepository.class);
        productRepository = mock(ProductRepository.class);
        service = new ProductVariantReadService(variantRepository, productRepository);
        publicCaller = mock(Authentication.class);
        doReturn(List.<GrantedAuthority>of()).when(publicCaller).getAuthorities();
        adminCaller = mock(Authentication.class);
        doReturn(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))).when(adminCaller)
                .getAuthorities();
    }

    private static Product activeProduct(String title) {
        // register without a goods schema: the factory's own ACTIVE path.
        return Product.register("b5a-cat", title, null, 1000L, "SAR", UUID.randomUUID());
    }

    private static Product suspendedProduct(String title) {
        Product product = Product.register("b5a-cat", title, null, 1000L, "SAR", UUID.randomUUID());
        product.suspend();
        return product;
    }

    private static ProductVariant activeVariant(Product product, String sku) {
        return ProductVariant.register(product, sku, "b5a " + sku, 900L);
    }

    private static ProductVariant suspendedVariant(Product product, String sku) {
        ProductVariant variant = ProductVariant.register(product, sku, "b5a " + sku, 900L);
        variant.suspend();
        return variant;
    }

    /** BaseEntity's audit instant is the listener's own write — set it directly for the keyset rows. */
    private static void stampUpdatedAt(ProductVariant variant, Instant instant) {
        try {
            Field field = com.marketplace.shared.jpa.BaseEntity.class.getDeclaredField("updatedAt");
            field.setAccessible(true);
            field.set(variant, instant);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("test stamp failed", exception);
        }
    }

    // ------------------------------------------------------------------
    // findVariantById — the permission-aware readable population
    // ------------------------------------------------------------------

    @Test
    void nullKeyAnswersEmpty() {
        assertThat(service.findVariantById(null, publicCaller)).isEmpty();
        verify(variantRepository, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void unknownKeyAnswersTheSafeAbsence() {
        UUID unknown = UUID.randomUUID();
        when(variantRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.findVariantById(unknown, publicCaller)).isEmpty();
        assertThat(service.findVariantById(unknown, adminCaller)).isEmpty();
    }

    @Test
    void activeVariantUnderActiveParentIsReadableByAnyCaller() {
        Product parent = activeProduct("b5a Parent Widget");
        ProductVariant variant = activeVariant(parent, "b5a-sku-1");
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        Optional<ProductVariantView> publicView = service.findVariantById(variant.getId(), publicCaller);
        assertThat(publicView).isPresent();
        assertThat(publicView.get().sku()).isEqualTo("b5a-sku-1");
        assertThat(publicView.get().status()).isEqualTo("ACTIVE");
        assertThat(publicView.get().name()).isEqualTo("b5a b5a-sku-1");
        assertThat(publicView.get().product()).isEqualTo(new ProductView(
                parent.getId(), parent.getTitle(), parent.getProductTypeId(), "ACTIVE"));

        assertThat(service.findVariantById(variant.getId(), adminCaller)).isPresent();
    }

    @Test
    void activeVariantUnderSuspendedParentIsReadableOnlyByTheAdminCapability() {
        Product parent = suspendedProduct("b5a Suspended Parent");
        ProductVariant variant = activeVariant(parent, "b5a-sku-2");
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        // The two-row data-scope negative: the variant's own shelf is
        // ACTIVE, the parent's is not — the public caller answers the
        // safe empty absence.
        assertThat(service.findVariantById(variant.getId(), publicCaller)).isEmpty();
        assertThat(service.findVariantById(variant.getId(), adminCaller)).isPresent();
    }

    @Test
    void suspendedVariantIsReadableOnlyByTheAdminCapability() {
        Product parent = activeProduct("b5a Parent Widget");
        ProductVariant variant = suspendedVariant(parent, "b5a-sku-3");
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.of(parent));

        assertThat(service.findVariantById(variant.getId(), publicCaller)).isEmpty();
        assertThat(service.findVariantById(variant.getId(), adminCaller)).isPresent();
    }

    @Test
    void unresolvableParentAnswersTheSafeAbsenceEvenForTheAdminCapability() {
        Product parent = activeProduct("b5a Vanishing Parent");
        ProductVariant variant = activeVariant(parent, "b5a-sku-4");
        when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
        when(productRepository.findById(parent.getId())).thenReturn(Optional.empty());

        // Referential honesty: include-unpublished widens lifecycle
        // states, never integrity — a null Product! is never rendered.
        assertThat(service.findVariantById(variant.getId(), adminCaller)).isEmpty();
    }

    // ------------------------------------------------------------------
    // scrollVariants — the guards
    // ------------------------------------------------------------------

    @Test
    void missingSortIsRejected() {
        assertThatThrownBy(() -> service.scrollVariants(publicCaller, null,
                ScrollPosition.keyset(), Limit.of(10)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonPositivePageSizeIsRejected() {
        assertThatThrownBy(() -> service.scrollVariants(publicCaller,
                ProductVariantSort.of(ProductVariantSort.Field.ID, true),
                ScrollPosition.keyset(), Limit.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // scrollVariants — the ADMIN population (derived framework scroll)
    // ------------------------------------------------------------------

    @Test
    void adminScrollRidesTheDerivedUpdatedAtScrollAndTheWholeCount() {
        Product parent = activeProduct("b5a Admin Parent");
        ProductVariant variant = activeVariant(parent, "b5a-sku-5");
        Window<ProductVariant> frameworkWindow = Window.from(
                List.of(variant), i -> ScrollPosition.forward(Map.of(
                        "updatedAt", Instant.parse("2026-10-21T10:00:00Z"), "id", variant.getId())),
                true);
        when(variantRepository.findByOrderByUpdatedAtDescIdDesc(any(), any()))
                .thenReturn(frameworkWindow);
        when(variantRepository.count()).thenReturn(41L);
        when(productRepository.findAllById(any())).thenReturn(List.of(parent));

        ProductVariantPage page = service.scrollVariants(adminCaller,
                ProductVariantSort.of(ProductVariantSort.Field.LAST_MODIFIED_AT, false),
                ScrollPosition.keyset(), Limit.of(10));

        verify(variantRepository).findByOrderByUpdatedAtDescIdDesc(any(), any());
        assertThat(page.total()).isEqualTo(41L);
        assertThat(page.window().hasNext()).isTrue();
        assertThat(page.window().getContent()).hasSize(1);
        assertThat(page.window().getContent().get(0).product().name()).isEqualTo("b5a Admin Parent");
    }

    @Test
    void adminScrollRidesTheDerivedIdScroll() {
        Product parent = activeProduct("b5a Admin Parent");
        ProductVariant variant = activeVariant(parent, "b5a-sku-6");
        Window<ProductVariant> frameworkWindow = Window.from(
                List.of(variant), i -> ScrollPosition.forward(Map.of("id", variant.getId())), false);
        when(variantRepository.findByOrderByIdAsc(any(), any())).thenReturn(frameworkWindow);
        when(variantRepository.count()).thenReturn(1L);
        when(productRepository.findAllById(any())).thenReturn(List.of(parent));

        ProductVariantPage page = service.scrollVariants(adminCaller,
                ProductVariantSort.of(ProductVariantSort.Field.ID, true),
                ScrollPosition.keyset(), Limit.of(10));

        verify(variantRepository).findByOrderByIdAsc(any(), any());
        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.window().getContent()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // scrollVariants — the PUBLIC population (native two-row-gated scroll)
    // ------------------------------------------------------------------

    @Test
    void publicScrollRidesTheNativeIdScrollWithThePlusOneFetch() {
        Product parent = activeProduct("b5a Public Parent");
        ProductVariant variant = activeVariant(parent, "b5a-sku-7");
        when(variantRepository.scrollPublicByIdAsc(null, 11)).thenReturn(List.of(variant));
        when(variantRepository.countPublicPopulation()).thenReturn(7L);
        when(productRepository.findAllById(any())).thenReturn(List.of(parent));

        ProductVariantPage page = service.scrollVariants(publicCaller,
                ProductVariantSort.of(ProductVariantSort.Field.ID, true),
                ScrollPosition.keyset(), Limit.of(10));

        // The pageSize+1 fetch contract: hasNext is the database's own answer.
        verify(variantRepository).scrollPublicByIdAsc(null, 11);
        assertThat(page.total()).isEqualTo(7L);
        assertThat(page.window().hasNext()).isFalse();
        assertThat(page.window().getContent()).hasSize(1);
        // The window's own position carries the ID sort's single key.
        ScrollPosition position = page.window().positionAt(0);
        assertThat(((KeysetScrollPosition) position).getKeys())
                .isEqualTo(Map.of("id", variant.getId()));
    }

    @Test
    void publicScrollTruncatesTheFullFetchToThePageAndAnswersHasNext() {
        Product parent = activeProduct("b5a Public Parent");
        ProductVariant first = activeVariant(parent, "b5a-sku-8");
        ProductVariant second = activeVariant(parent, "b5a-sku-9");
        when(variantRepository.scrollPublicByIdAsc(null, 3))
                .thenReturn(List.of(first, second, activeVariant(parent, "b5a-sku-10")));
        when(variantRepository.countPublicPopulation()).thenReturn(30L);
        when(productRepository.findAllById(any())).thenReturn(List.of(parent));

        ProductVariantPage page = service.scrollVariants(publicCaller,
                ProductVariantSort.of(ProductVariantSort.Field.ID, true),
                ScrollPosition.keyset(), Limit.of(2));

        assertThat(page.window().hasNext()).isTrue();
        assertThat(page.window().getContent()).hasSize(2);
        assertThat(page.total()).isEqualTo(30L);
    }

    @Test
    void publicUpdatedAtScrollBindsTheDecodedCursorKeys() {
        Product parent = activeProduct("b5a Public Parent");
        ProductVariant variant = activeVariant(parent, "b5a-sku-11");
        stampUpdatedAt(variant, Instant.parse("2026-10-21T11:00:00Z"));
        Instant cursorInstant = Instant.parse("2026-10-21T10:00:00Z");
        UUID cursorId = UUID.randomUUID();
        KeysetScrollPosition cursor = (KeysetScrollPosition) ScrollPosition.forward(Map.of(
                "updatedAt", cursorInstant, "id", cursorId));
        OffsetDateTime expectedBind = cursorInstant.atOffset(ZoneOffset.UTC);
        when(variantRepository.scrollPublicByUpdatedAtDesc(expectedBind, cursorId, 11))
                .thenReturn(List.of(variant));
        when(variantRepository.countPublicPopulation()).thenReturn(1L);
        when(productRepository.findAllById(any())).thenReturn(List.of(parent));

        ProductVariantPage page = service.scrollVariants(publicCaller,
                ProductVariantSort.of(ProductVariantSort.Field.LAST_MODIFIED_AT, false),
                cursor, Limit.of(10));

        verify(variantRepository).scrollPublicByUpdatedAtDesc(expectedBind, cursorId, 11);
        assertThat(page.window().getContent()).hasSize(1);
        // The window's own position carries the executed sort's keys with
        // the ROW's values — the same vocabulary the framework emits.
        assertThat(((KeysetScrollPosition) page.window().positionAt(0)).getKeys())
                .isEqualTo(Map.of("updatedAt", Instant.parse("2026-10-21T11:00:00Z"),
                        "id", variant.getId()));
    }

    @Test
    void publicScrollInTheOtherDirectionsReachesTheDescendingMethods() {
        Product parent = activeProduct("b5a Public Parent");
        ProductVariant variant = activeVariant(parent, "b5a-sku-12");
        stampUpdatedAt(variant, Instant.parse("2026-10-21T09:00:00Z"));
        when(variantRepository.scrollPublicByIdDesc(null, 6)).thenReturn(List.of(variant));
        when(variantRepository.scrollPublicByUpdatedAtAsc(null, null, 6))
                .thenReturn(List.of(variant));
        when(variantRepository.countPublicPopulation()).thenReturn(1L);
        when(productRepository.findAllById(any())).thenReturn(List.of(parent));

        service.scrollVariants(publicCaller,
                ProductVariantSort.of(ProductVariantSort.Field.ID, false),
                ScrollPosition.keyset(), Limit.of(5));
        verify(variantRepository).scrollPublicByIdDesc(null, 6);

        service.scrollVariants(publicCaller,
                ProductVariantSort.of(ProductVariantSort.Field.LAST_MODIFIED_AT, true),
                ScrollPosition.keyset(), Limit.of(5));
        verify(variantRepository).scrollPublicByUpdatedAtAsc(null, null, 6);
    }

    @Test
    void publicScrollBatchesTheParentProjections() {
        Product parent = activeProduct("b5a Shared Parent");
        ProductVariant first = activeVariant(parent, "b5a-sku-13");
        ProductVariant second = activeVariant(parent, "b5a-sku-14");
        when(variantRepository.scrollPublicByIdAsc(null, 3)).thenReturn(List.of(first, second));
        when(variantRepository.countPublicPopulation()).thenReturn(2L);
        when(productRepository.findAllById(any())).thenReturn(List.of(parent));

        ProductVariantPage page = service.scrollVariants(publicCaller,
                ProductVariantSort.of(ProductVariantSort.Field.ID, true),
                ScrollPosition.keyset(), Limit.of(2));

        // ONE batched findAllById over the page's distinct parents — never a per-row N+1.
        verify(productRepository).findAllById(any());
        assertThat(page.window().getContent())
                .allSatisfy(view -> assertThat(view.product().name()).isEqualTo("b5a Shared Parent"));
        assertThat(page.window().getContent()).hasSize(2);
    }
}
