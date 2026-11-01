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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        when(variantReadSpi.findVariantForCheckoutLine(variantId)).thenReturn(Optional.of(view));
        when(variantMapper.toGraphQlProductVariant(view)).thenReturn(wireVariant);

        Map<String, Object> mapped = mapper.render(checkout).checkout();
        @SuppressWarnings("unchecked")
        Map<String, Object> mappedLine = (Map<String, Object>) ((List<?>) mapped.get("lines")).get(0);

        assertThat(mappedLine.get("variant")).isSameAs(wireVariant);
        assertThat(mappedLine.get("variant")).isInstanceOf(ProductVariant.class);
        assertThat(mappedLine).doesNotContainKeys("availableInCatalog", "productVariant");
        verify(variantReadSpi).findVariantForCheckoutLine(variantId);
        verify(variantMapper).toGraphQlProductVariant(view);
    }

    @Test
    void neverReportsTheLegacyProductLevelLineForThePurge() {
        // The wave-4 #806 review's SECOND cycle (Major · Data Integrity):
        // a product-level line (variantId == null — ADR-0012's deliberate
        // legacy cart state, still created by CartController.addItem and
        // served by the legacy REST cart surface with its id) is NOT
        // staleness. It is excluded from the Saleor wire render (the
        // pinned reference schema saleor/saleor@3.23.43 declares
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
        when(variantReadSpi.findVariantForCheckoutLine(staleVariantId))
                .thenReturn(Optional.empty());
        ProductVariantView liveView = new ProductVariantView(
                liveVariantId, "variant-name", "seller-sku", "ACTIVE",
                new ProductView(UUID.randomUUID(), "parent-name", null, "ACTIVE"));
        when(variantReadSpi.findVariantForCheckoutLine(liveVariantId))
                .thenReturn(Optional.of(liveView));
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
        verify(variantReadSpi).findVariantForCheckoutLine(staleVariantId);
        verify(variantReadSpi).findVariantForCheckoutLine(liveVariantId);
    }

    @Test
    void reportsTheLineWhoseVariantNoLongerResolvesForTheOwnerSidePurge() {
        UUID lineId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        when(variantReadSpi.findVariantForCheckoutLine(variantId)).thenReturn(Optional.empty());
        CheckoutView checkout = new CheckoutView(
                UUID.randomUUID(),
                List.of(new CheckoutView.Line(lineId, variantId, 1)),
                1);

        CheckoutGraphQlMapper.RenderedCheckout rendered = mapper.render(checkout);

        assertThat(rendered.checkout().get("lines")).isEqualTo(List.of());
        assertThat(rendered.staleLineIds()).containsExactly(lineId);
        verify(variantReadSpi).findVariantForCheckoutLine(variantId);
    }
}
