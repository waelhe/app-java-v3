package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.orders.spi.CheckoutView;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps the orders owner's bounded checkout projection onto the Saleor-shaped
 * wire type. The adapter owns typed IDs and transport shape only; ownership
 * and lifecycle gates stay in the owning use case.
 */
@Component
public class CheckoutGraphQlMapper {

    static final String CHECKOUT_TYPE = "Checkout";
    static final String CHECKOUT_LINE_TYPE = "CheckoutLine";

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
        checkout.put("lines", lines);
        checkout.put("quantity", view.quantity());
        return checkout;
    }

    private Map<String, Object> toGraphQlLine(CheckoutView.Line line) {
        var variant = variantReadSpi.findVariantSnapshot(line.variantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ProductVariant", line.variantId()));
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("id", globalIdCodec.encode(CHECKOUT_LINE_TYPE, line.id()));
        mapped.put("variant", variantMapper.toGraphQlProductVariant(variant));
        mapped.put("quantity", line.quantity());
        return mapped;
    }
}
