package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.ProductVariant;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.orders.spi.CheckoutView;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps checkout lines to Saleor's direct non-null ProductVariant contract.
 * Checkout ownership has already been checked by CheckoutSpi before this
 * mapper is reached. The dedicated catalog read preserves live variant
 * projections across lifecycle visibility changes without widening public
 * storefront ProductVariant reads.
 *
 * <p>A legacy product-level cart line (no variant UUID), or a line whose
 * variant/parent was soft-deleted or is missing, cannot truthfully satisfy
 * ProductVariant!; it fails closed rather than inventing a variant.</p>
 */
@Component
public class CheckoutGraphQlMapper {

    static final String CHECKOUT_TYPE = "Checkout";
    static final String CHECKOUT_LINE_TYPE = "CheckoutLine";
    static final String PRODUCT_VARIANT_TYPE = "ProductVariant";

    private final SaleorGlobalIdCodec globalIdCodec;
    private final ProductVariantReadSpi variantReadSpi;
    private final ProductVariantGraphQlMapper variantMapper;

    public CheckoutGraphQlMapper(SaleorGlobalIdCodec globalIdCodec,
                                 ProductVariantReadSpi variantReadSpi,
                                 ProductVariantGraphQlMapper variantMapper) {
        this.globalIdCodec = globalIdCodec;
        this.variantReadSpi = variantReadSpi;
        this.variantMapper = variantMapper;
    }

    public Map<String, Object> toGraphQlCheckout(CheckoutView view) {
        List<Map<String, Object>> lines = view.lines().stream()
                .map(this::toGraphQlLine)
                .toList();
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("id", globalIdCodec.encode(CHECKOUT_TYPE, view.id()));
        // Saleor Checkout.token is the same native UUID primary key, not a
        // second secret/token store. The authenticated owner/ADMIN gate ran
        // before this mapper is reached.
        checkout.put("token", view.id().toString());
        checkout.put("lines", lines);
        checkout.put("quantity", view.quantity());
        return checkout;
    }

    private Map<String, Object> toGraphQlLine(CheckoutView.Line line) {
        if (line.variantId() == null) {
            throw unresolvableCheckoutVariant();
        }
        com.marketplace.app.graphql.generated.types.ProductVariant variant =
                variantReadSpi.findVariantForCheckoutLine(line.variantId())
                        .map(variantMapper::toGraphQlProductVariant)
                        .orElseThrow(CheckoutGraphQlMapper::unresolvableCheckoutVariant);

        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("id", globalIdCodec.encode(CHECKOUT_LINE_TYPE, line.id()));
        mapped.put("variant", variant);
        mapped.put("quantity", line.quantity());
        return mapped;
    }

    private static IllegalStateException unresolvableCheckoutVariant() {
        return new IllegalStateException(
                "Checkout line cannot be represented because its product variant or parent record is unavailable");
    }
}
