package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.ProductVariant;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.catalog.spi.ProductView;
import com.marketplace.orders.spi.CheckoutView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CheckoutGraphQlMapperTest {

    private final SaleorGlobalIdCodec globalIdCodec = mock(SaleorGlobalIdCodec.class);
    private final ProductVariantReadSpi variantReadSpi = mock(ProductVariantReadSpi.class);
    private final ProductVariantGraphQlMapper variantMapper = mock(ProductVariantGraphQlMapper.class);
    private final CheckoutGraphQlMapper mapper =
            new CheckoutGraphQlMapper(globalIdCodec, variantReadSpi, variantMapper);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void mapsCheckoutLineVariantAsTheDirectProductVariantType() {
        UUID cartId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CheckoutView.Line line = new CheckoutView.Line(lineId, variantId, 2);
        CheckoutView checkout = new CheckoutView(cartId, List.of(line), 2);
        ProductVariantView view = new ProductVariantView(
                variantId, "variant-name", "seller-sku", "SUSPENDED",
                new ProductView(productId, "parent-name", null, "SUSPENDED"));
        ProductVariant wireVariant = mock(ProductVariant.class);
        when(variantReadSpi.findVariantsForCheckoutLines(Set.of(variantId)))
                .thenReturn(Map.of(variantId, view));
        when(variantMapper.toGraphQlProductVariant(view)).thenReturn(wireVariant);

        Map<String, Object> mapped = mapper.render(checkout).checkout();
        @SuppressWarnings("unchecked")
        Map<String, Object> mappedLine = (Map<String, Object>) ((List<?>) mapped.get("lines")).get(0);

        assertThat(mappedLine.get("variant")).isSameAs(wireVariant);
        assertThat(mappedLine.get("variant")).isInstanceOf(ProductVariant.class);
        assertThat(mappedLine).doesNotContainKeys("availableInCatalog", "productVariant");
        verify(variantReadSpi).findVariantsForCheckoutLines(Set.of(variantId));
        verify(variantMapper).toGraphQlProductVariant(view);
    }

    @Test
    void neverReportsTheLegacyProductLevelLineForThePurge() {
        // The wave-4 #806 review's SECOND cycle (Major · Data Integrity):
        // a product-level line (variantId == null — ADR-0012's deliberate
        // legacy cart state, still created by CartController.addItem and
        // served by the legacy REST cart surface with its id) is NOT
        // staleness. It is excluded from the Saleor wire render (the
        // pinned reference schema saleor/saleor@3.23.45 declares
        // CheckoutLine.variant: ProductVariant! — never fabricated) but
        // it must NEVER be reported for the purge: the earlier contract
        // fed it to checkoutSpi.deleteLines on every checkout read and
        // deleted a legitimate, purchasable line from under its owner.
        UUID lineId = UUID.randomUUID();
        CheckoutView checkout = new CheckoutView(
                UUID.randomUUID(),
                List.of(new CheckoutView.Line(lineId, null, 1)),
                1);

        CheckoutGraphQlMapper.RenderedCheckout rendered = mapper.render(checkout);

        assertThat(rendered.checkout().get("lines")).isEqualTo(List.of());
        assertThat(rendered.checkout().get("quantity")).isEqualTo(0);
        assertThat(rendered.staleLineIds()).isEmpty();
        verifyNoInteractions(variantReadSpi, variantMapper);
    }

    @Test
    void distinguishesTheProductLevelLineFromTheStaleVariantLineInTheSameCart() {
        // The two unrenderable shapes carry OPPOSITE dispositions: the
        // product-level line stays unpurged (its home is the legacy REST
        // surface), while the line whose variant no longer resolves is
        // reported for the owner-authorized purge (the Saleor cascade
        // analog). One cart carrying both plus a live line pins the
        // discrimination the second-cycle review demanded.
        UUID productLevelLineId = UUID.randomUUID();
        UUID staleLineId = UUID.randomUUID();
        UUID liveLineId = UUID.randomUUID();
        UUID staleVariantId = UUID.randomUUID();
        UUID liveVariantId = UUID.randomUUID();
        ProductVariantView liveView = new ProductVariantView(
                liveVariantId, "variant-name", "seller-sku", "ACTIVE",
                new ProductView(UUID.randomUUID(), "parent-name", null, "ACTIVE"));
        when(variantReadSpi.findVariantsForCheckoutLines(Set.of(staleVariantId, liveVariantId)))
                .thenReturn(Map.of(liveVariantId, liveView));
        when(variantMapper.toGraphQlProductVariant(liveView)).thenReturn(mock(ProductVariant.class));
        when(globalIdCodec.encode(CheckoutGraphQlMapper.CHECKOUT_LINE_TYPE, liveLineId))
                .thenReturn("live-line-global-id");
        CheckoutView checkout = new CheckoutView(
                UUID.randomUUID(),
                List.of(
                        new CheckoutView.Line(productLevelLineId, null, 3),
                        new CheckoutView.Line(staleLineId, staleVariantId, 1),
                        new CheckoutView.Line(liveLineId, liveVariantId, 2)),
                6);

        CheckoutGraphQlMapper.RenderedCheckout rendered = mapper.render(checkout);

        assertThat(((List<?>) rendered.checkout().get("lines"))).hasSize(1);
        assertThat(rendered.checkout().get("quantity")).isEqualTo(2);
        assertThat(rendered.staleLineIds()).containsExactly(staleLineId);
        verify(variantReadSpi).findVariantsForCheckoutLines(Set.of(staleVariantId, liveVariantId));
    }

    @Test
    void reportsTheLineWhoseVariantNoLongerResolvesForTheOwnerSidePurge() {
        UUID lineId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        when(variantReadSpi.findVariantsForCheckoutLines(Set.of(variantId)))
                .thenReturn(Map.of());
        CheckoutView checkout = new CheckoutView(
                UUID.randomUUID(),
                List.of(new CheckoutView.Line(lineId, variantId, 1)),
                1);

        CheckoutGraphQlMapper.RenderedCheckout rendered = mapper.render(checkout);

        assertThat(rendered.checkout().get("lines")).isEqualTo(List.of());
        assertThat(rendered.staleLineIds()).containsExactly(lineId);
        verify(variantReadSpi).findVariantsForCheckoutLines(Set.of(variantId));
    }

    @Test
    void rendersTheWholeCartThroughOneBatchedVariantRead() {
        // The 12:44:42Z #806 review (Performance & Scalability): the
        // render reads the variants in ONE batched SPI call for the
        // WHOLE cart — never a per-line query on every checkout read
        // and mutation payload (the N+1 the review measured). Five
        // lines over three DISTINCT variants (duplicates collapse):
        // the batched call happens exactly once, keyed by the distinct
        // ids, and no other SPI read intervenes.
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        ProductVariantView view1 = new ProductVariantView(
                v1, "variant-one", "sku-one", "ACTIVE",
                new ProductView(UUID.randomUUID(), "parent-one", null, "ACTIVE"));
        ProductVariantView view2 = new ProductVariantView(
                v2, "variant-two", "sku-two", "ACTIVE",
                new ProductView(UUID.randomUUID(), "parent-two", null, "ACTIVE"));
        ProductVariantView view3 = new ProductVariantView(
                v3, "variant-three", "sku-three", "ACTIVE",
                new ProductView(UUID.randomUUID(), "parent-three", null, "ACTIVE"));
        when(variantReadSpi.findVariantsForCheckoutLines(Set.of(v1, v2, v3)))
                .thenReturn(Map.of(v1, view1, v2, view2, v3, view3));
        when(variantMapper.toGraphQlProductVariant(view1)).thenReturn(mock(ProductVariant.class));
        when(variantMapper.toGraphQlProductVariant(view2)).thenReturn(mock(ProductVariant.class));
        when(variantMapper.toGraphQlProductVariant(view3)).thenReturn(mock(ProductVariant.class));
        CheckoutView checkout = new CheckoutView(
                UUID.randomUUID(),
                List.of(
                        new CheckoutView.Line(UUID.randomUUID(), v1, 1),
                        new CheckoutView.Line(UUID.randomUUID(), v2, 2),
                        new CheckoutView.Line(UUID.randomUUID(), v3, 3),
                        new CheckoutView.Line(UUID.randomUUID(), v1, 4),
                        new CheckoutView.Line(UUID.randomUUID(), v2, 5)),
                15);

        CheckoutGraphQlMapper.RenderedCheckout rendered = mapper.render(checkout);

        assertThat(((List<?>) rendered.checkout().get("lines"))).hasSize(5);
        assertThat(rendered.checkout().get("quantity")).isEqualTo(15);
        assertThat(rendered.staleLineIds()).isEmpty();
        verify(variantReadSpi, times(1)).findVariantsForCheckoutLines(anyCollection());
        verifyNoMoreInteractions(variantReadSpi);
    }
}
