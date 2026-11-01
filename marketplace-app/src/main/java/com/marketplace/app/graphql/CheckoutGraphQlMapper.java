package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.orders.spi.CheckoutView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 *
 * <p><b>The stale-line contract (the wave-4 #806 review's Major · Data
 * Integrity finding):</b> {@link #render(CheckoutView)} reports every
 * skipped line's native id to its caller — {@code toGraphQlLine} alone
 * SKIPS the unrepresentable line (Saleor's canonical surface contract:
 * its {@code CheckoutLine.variant} FK cascades, so a deleted variant takes
 * its lines with it and every checkout read answers only REPRESENTABLE
 * lines), but a skip WITHOUT a report left the line stranded in the cart:
 * the buyer could not learn the line's id from any checkout query (the
 * line is absent from {@code lines}), while
 * {@code CheckoutDataFetcher.checkoutLineDelete} demands exactly that id —
 * the cart could never be reconciled. The fetcher layer now consumes the
 * report and purges the reported lines through the SAME owner-authorized
 * use case BEFORE the payload returns, so the persisted cart converges
 * with the rendered projection and no line id is ever hidden from its
 * owner. The mapper itself stays a PURE conversion — no writes, no
 * transactional semantics.</p>
 */
@Component
public class CheckoutGraphQlMapper {

    static final String CHECKOUT_TYPE = "Checkout";
    static final String CHECKOUT_LINE_TYPE = "CheckoutLine";
    static final String PRODUCT_VARIANT_TYPE = "ProductVariant";

    /**
     * The rendered checkout plus the native ids of the lines that could
     * NOT be rendered (the stale/deleted-variant lines the fetcher layer
     * purges through the owner-authorized delete use case).
     */
    record RenderedCheckout(Map<String, Object> checkout, List<UUID> staleLineIds) {
    }

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

    /**
     * Renders the checkout AND reports the unrenderable lines: the
     * wave-4 #806 review's Major finding — a silent skip stranded the
     * stale line in the cart with its id undiscoverable, while
     * {@code checkoutLineDelete} demands exactly that id. The rendered
     * total is the sum of the rendered lines (the honest wire quantity).
     */
    RenderedCheckout render(CheckoutView view) {
        List<Map<String, Object>> lines = new ArrayList<>();
        List<UUID> staleLineIds = new ArrayList<>();
        for (CheckoutView.Line line : view.lines()) {
            Map<String, Object> rendered = toGraphQlLine(line);
            if (rendered != null) {
                lines.add(rendered);
            } else {
                staleLineIds.add(line.id());
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
        return new RenderedCheckout(checkout, List.copyOf(staleLineIds));
    }

    /**
     * The representable form of one checkout line — {@code null} when the
     * line is NOT representable (the stale/deleted-variant case the
     * wave-4 review measured): the {@code CheckoutLine.variant} field is
     * {@code ProductVariant!} (non-null in the vendored Saleor SDL), so
     * an unrepresentable line cannot be served as a weaker shape — it is
     * absent from the render and REPORTED for the owner-side purge
     * instead, per the cascade contract above.
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
