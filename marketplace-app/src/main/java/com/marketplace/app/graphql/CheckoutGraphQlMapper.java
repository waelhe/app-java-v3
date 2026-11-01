package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.orders.spi.CheckoutView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // The wave-4 review (Major · Stability & Availability, agent-3
        // orders-events SDL line 117): a stale line (no variantId) or a
        // line pointing at a deleted variant previously THREW during the
        // whole-checkout conversion — the checkout query AND every
        // line-mutation payload failed, the buyer could neither read the
        // checkout nor delete the offending line, and checkoutDelete
        // demands ADMIN. Saleor's canonical contract never surfaces such
        // a line at all: its CheckoutLine.variant FK cascades, so a
        // deleted variant takes its lines with it and every checkout
        // read answers only REPRESENTABLE lines. This mapper adopts the
        // same surface contract: dead lines are SKIPPED (never thrown
        // into an unreadable checkout), the rendered total is the sum of
        // the rendered lines (the honest wire quantity), and the line
        // stays deletable by id through checkoutLineDelete for the
        // owner-side reconciliation.
        List<Map<String, Object>> lines = new ArrayList<>();
        for (CheckoutView.Line line : view.lines()) {
            Map<String, Object> rendered = toGraphQlLine(line);
            if (rendered != null) {
                lines.add(rendered);
            }
        }
        Map<String, Object> checkout = new LinkedHashMap<>();
        checkout.put("id", globalIdCodec.encode(CHECKOUT_TYPE, view.id()));
        // Saleor Checkout.token is the same native UUID primary key, not a
        // second secret/token store. The authenticated owner/ADMIN gate ran
        // before this mapper is reached.
        checkout.put("token", view.id().toString());
        checkout.put("lines", lines);
        checkout.put("quantity", lines.stream()
                .mapToInt(rendered -> ((Number) rendered.get("quantity")).intValue())
                .sum());
        return checkout;
    }

    /**
     * The representable form of one checkout line — {@code null} when the
     * line is NOT representable (the stale/deleted-variant case the
     * wave-4 review measured): the {@code CheckoutLine.variant} field is
     * {@code ProductVariant!} (non-null in the vendored Saleor SDL), so
     * an unrepresentable line cannot be served as a weaker shape — it is
     * absent instead, per the cascade contract above.
     */
    private Map<String, Object> toGraphQlLine(CheckoutView.Line line) {
        if (line.variantId() == null) {
            return null;
        }
        return variantReadSpi.findVariantForCheckoutLine(line.variantId())
                .map(variantMapper::toGraphQlProductVariant)
                .map(variant -> {
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put("id", globalIdCodec.encode(CHECKOUT_LINE_TYPE, line.id()));
                    mapped.put("variant", variant);
                    mapped.put("quantity", line.quantity());
                    return mapped;
                })
                .orElse(null);
    }
}
