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
    void reportsTheLegacyProductLevelLineForTheOwnerSidePurge() {
        // The wave-4 #806 review (Major · Data Integrity): the line is not
        // representable on the wire, but a silent skip stranded it in the
        // cart with its id undiscoverable — the render REPORTS the stale
        // line's native id so the fetcher layer purges it through the same
        // owner-authorized use case checkoutLineDelete rides.
        UUID lineId = UUID.randomUUID();
        CheckoutView checkout = new CheckoutView(
                UUID.randomUUID(),
                List.of(new CheckoutView.Line(lineId, null, 1)),
                1);

        CheckoutGraphQlMapper.RenderedCheckout rendered = mapper.render(checkout);

        assertThat(rendered.checkout().get("lines")).isEqualTo(List.of());
        assertThat(rendered.staleLineIds()).containsExactly(lineId);
        verifyNoInteractions(variantReadSpi, variantMapper);
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
