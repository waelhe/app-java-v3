package com.marketplace.app.graphql;

import com.marketplace.catalog.spi.ProductVariantReadSpi;
import com.marketplace.catalog.spi.ProductVariantView;
import com.marketplace.orders.spi.CheckoutView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Maps checkout lines to Saleor's direct non-null ProductVariant contract.
 * Checkout ownership has already been checked by CheckoutSpi before this
 * mapper is reached. The dedicated catalog read preserves live variant
 * projections across lifecycle visibility changes without widening public
 * storefront ProductVariant reads.
 *
 * <p><b>The canonical constraint (the pinned reference schema —
 * saleor/saleor@3.23.45, {@code saleor/graphql/schema.graphql}):</b>
 * {@code CheckoutLine.variant} is {@code ProductVariant!} — non-null. A
 * cart line that carries no variant can never be rendered as a
 * {@code CheckoutLine} without fabricating a variant, which this mapper
 * never does (fail closed, never invent a variant).</p>
 *
 * <p><b>The two unrenderable shapes are NOT one case (the wave-4 #806
 * review's second-cycle Major · Data Integrity finding):</b></p>
 * <ul>
 *   <li><b>A product-level line ({@code variantId == null}) is NOT
 *   stale.</b> It is the legacy cart model's deliberate state (ADR-0012:
 *   the zero-variant rule — the product-level price and shelf stay the
 *   authoritative record; {@code CartController.addItem} still creates
 *   it). It is excluded from the Saleor wire render (the canonical
 *   non-null variant above) but it is <b>never reported and never
 *   purged</b> — the earlier purge-on-read treated it as stale and
 *   DELETED a legitimate, purchasable line from under its owner. Its
 *   visibility and management home is the legacy REST cart surface
 *   ({@code GET /cart} returns every line with its id — the same surface
 *   that created it), exactly as the orders owner's own projection
 *   contract documents ({@code OrdersService.checkoutView}: "never
 *   deleted from the legacy cart … still served by the legacy REST
 *   surface").</li>
 *   <li><b>A stale variant line ({@code variantId != null} whose variant
 *   no longer resolves through {@code findVariantsForCheckoutLines}) IS
 *   stale.</b> {@link #render(CheckoutView)} reports its native id so the
 *   fetcher layer purges it through the same owner-authorized use case
 *   {@code checkoutLineDelete} rides — Saleor's canonical cascade
 *   contract ({@code CheckoutLine.variant} FK cascades: a deleted variant
 *   takes its lines with it), so the persisted cart converges with the
 *   rendered projection and the line's id never strands its owner.</li>
 * </ul>
 *
 * <p>The mapper itself stays a PURE conversion — no writes, no
 * transactional semantics; the purge decision it reports and the fetcher
 * executes is bounded to the second shape alone.</p>
 */
@Component
public class CheckoutGraphQlMapper {

    static final String CHECKOUT_TYPE = "Checkout";
    static final String CHECKOUT_LINE_TYPE = "CheckoutLine";
    static final String PRODUCT_VARIANT_TYPE = "ProductVariant";

    /**
     * The rendered checkout plus the native ids of the STALE VARIANT lines
     * only — lines that carry a variant UUID whose variant no longer
     * resolves, which the fetcher layer purges through the owner-authorized
     * delete use case. A product-level line ({@code variantId == null}) is
     * never in this report: it is legitimate legacy state, not staleness
     * (see the class contract above).
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
     * Renders the checkout AND reports the stale VARIANT lines for the
     * owner-side purge. A product-level line is skipped from the wire
     * render (the canonical non-null variant) but NEVER reported — it is
     * legitimate legacy cart state whose id stays discoverable through
     * the legacy REST cart surface; purging it on read would delete a
     * purchasable line from under its owner (the wave-4 #806 review's
     * second-cycle Major · Data Integrity finding). The rendered total is
     * the sum of the rendered lines (the honest wire quantity).
     */
    RenderedCheckout render(CheckoutView view) {
        List<Map<String, Object>> lines = new ArrayList<>();
        List<UUID> staleLineIds = new ArrayList<>();
        // The 12:44:42Z #806 review (Performance & Scalability): the
        // variant reads ride ONE batched SPI call for the WHOLE cart
        // (the parentProjections batched-association discipline the
        // catalog owner already carries) — never a per-line query on
        // every checkout read and every mutation payload (the N+1 the
        // review measured). Duplicates collapse to one read per DISTINCT
        // variant; a cart with no variant-backed lines reads nothing.
        Set<UUID> variantIds = new LinkedHashSet<>();
        for (CheckoutView.Line line : view.lines()) {
            if (line.variantId() != null) {
                variantIds.add(line.variantId());
            }
        }
        Map<UUID, ProductVariantView> variants = variantIds.isEmpty()
                ? Map.of()
                : variantReadSpi.findVariantsForCheckoutLines(variantIds);
        for (CheckoutView.Line line : view.lines()) {
            if (line.variantId() == null) {
                // ADR-0012 product-level line: legacy cart state, NOT
                // staleness — excluded from the Saleor wire render
                // (canonical CheckoutLine.variant: ProductVariant! is
                // never fabricated), never reported for the purge. Its
                // visibility and management home is the legacy REST cart
                // surface (GET /cart carries the line with its id).
                continue;
            }
            ProductVariantView variant = variants.get(line.variantId());
            if (variant == null) {
                // The stale/deleted-variant shape: absent from the
                // batched read — REPORTED for the owner-side purge (the
                // cascade contract the class documents).
                staleLineIds.add(line.id());
            } else {
                lines.add(toGraphQlLine(line, variant));
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
     * The representable form of one checkout line over its ALREADY-BATCHED
     * variant projection — the stale/deleted-variant case (the variant
     * absent from {@link #render}'s batched read, the shape the wave-4
     * review measured) never reaches this method: the
     * {@code CheckoutLine.variant} field is {@code ProductVariant!}
     * (non-null in the vendored Saleor SDL and in the pinned reference
     * schema saleor/saleor@3.23.45), so {@link #render} reports such a
     * line for the owner-side purge instead, per the cascade contract
     * above. The product-level ({@code variantId == null}) shape is
     * skipped before the variant read, keeping the two shapes'
     * dispositions distinct.
     */
    private Map<String, Object> toGraphQlLine(CheckoutView.Line line, ProductVariantView variant) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        mapped.put("id", globalIdCodec.encode(CHECKOUT_LINE_TYPE, line.id()));
        mapped.put("variant", variantMapper.toGraphQlProductVariant(variant));
        mapped.put("quantity", line.quantity());
        return mapped;
    }
}
