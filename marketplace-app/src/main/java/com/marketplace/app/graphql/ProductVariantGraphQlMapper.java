package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Product;
import com.marketplace.app.graphql.generated.types.ProductVariant;
import com.marketplace.catalog.spi.ProductVariantView;
import org.springframework.stereotype.Component;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productVariant}
 * and {@code productVariants} read roots, packet campaign-b5-a): the
 * transport mapping of the catalog owner's bounded product-variant
 * projection onto the Saleor-shaped {@code ProductVariant} wire type.
 * This class owns ONLY transport mapping — the global-ID encodes and
 * the platform extension fact's wire rendering — never a business rule
 * (the same boundary discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits (type tag {@code ProductVariant} —
 * opaque on the wire, the owner's native {@code product_variants} key
 * behind it). The {@code product} field renders the parent's bounded
 * projection through the EXISTING {@link ProductGraphQlMapper} — one
 * Product wire shape across the product roots and the variant roots,
 * one codec, one ID vocabulary. The declared closure is exactly the
 * bounded projection's own facts: {@code name} the variant's own
 * optional display name (the honest null of a legacy free-form
 * variant — the registered nullable-name divergence), {@code sku} the
 * seller's own SKU, and {@code status} the availability lifecycle
 * state name — a PLATFORM EXTENSION fact the registry owns and the
 * Saleor wire shape does not carry, preserved per the governing
 * matrix's own platform-only-fields-as-extensions policy. Unowned
 * Saleor fields stay undeclared until an owning capability exists —
 * never faked.</p>
 */
@Component
public class ProductVariantGraphQlMapper {

    /** The GraphQL type tag of the Saleor ProductVariant type — the codec's own contract. */
    static final String PRODUCT_VARIANT_TYPE = "ProductVariant";

    private final SaleorGlobalIdCodec globalIdCodec;
    private final ProductGraphQlMapper productMapper;

    public ProductVariantGraphQlMapper(SaleorGlobalIdCodec globalIdCodec,
                                       ProductGraphQlMapper productMapper) {
        this.globalIdCodec = globalIdCodec;
        this.productMapper = productMapper;
    }

    /**
     * Maps one variant projection onto the wire type.
     *
     * @param view the catalog owner's bounded variant projection
     * @return the Saleor-shaped variant projection (with its documented
     *         platform extension fact)
     */
    public ProductVariant toGraphQlProductVariant(ProductVariantView view) {
        Product parent = productMapper.toGraphQlProduct(view.product());
        return ProductVariant.newBuilder()
                .id(globalIdCodec.encode(PRODUCT_VARIANT_TYPE, view.id()))
                .name(view.name())
                .sku(view.sku())
                .product(parent)
                .status(view.status())
                .build();
    }
}
