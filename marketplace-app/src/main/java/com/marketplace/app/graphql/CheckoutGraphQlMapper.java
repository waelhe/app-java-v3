package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.ProductVariant;
import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.orders.spi.CheckoutView;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a persisted checkout-line variant reference separately from the
 * catalog's current readable projection. A cart line may outlive public
 * catalog visibility, so the committed line remains representable without
 * pretending an unreadable variant is a not-found checkout.
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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        List<Map<String, Object>> lines = view.lines().stream()
                .map(line -> toGraphQlLine(line, authentication))
                .toList();
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("id", globalIdCodec.encode(CHECKOUT_TYPE, view.id()));
        checkout.put("lines", lines);
        checkout.put("quantity", view.quantity());
        return checkout;
    }

    private Map<String, Object> toGraphQlLine(CheckoutView.Line line, Authentication authentication) {
        Optional<ProductVariantView> variant =
                variantReadSpi.findVariantById(line.variantId(), authentication);
        Map<String, Object> variantReference = new LinkedHashMap<>();
        variantReference.put("id", globalIdCodec.encode(PRODUCT_VARIANT_TYPE, line.variantId()));
        variantReference.put("availableInCatalog", variant.isPresent());
        ProductVariant catalogProjection = variant.map(variantMapper::toGraphQlProductVariant).orElse(null);
        variantReference.put("productVariant", catalogProjection);

        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("id", globalIdCodec.encode(CHECKOUT_LINE_TYPE, line.id()));
        mapped.put("variant", variantReference);
        mapped.put("quantity", line.quantity());
        return mapped;
    }
}
