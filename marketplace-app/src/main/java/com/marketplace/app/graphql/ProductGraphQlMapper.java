package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Product;
import com.marketplace.catalog.spi.ProductView;
import org.springframework.stereotype.Component;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code product} and
 * {@code products} read roots, packet campaign-b4-c): the transport
 * mapping of the catalog owner's bounded product projection onto the
 * Saleor-shaped {@code Product} wire type. This class owns ONLY
 * transport mapping — the global-ID encodes and the platform extension
 * fact's wire rendering — never a business rule (the same boundary
 * discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits (type tag {@code Product} — opaque
 * on the wire, the owner's native {@code products} key behind it). The
 * {@code productType} field renders the optional reusable-goods-schema
 * reference as the typed global ID of the EXISTING ProductType root's
 * type (the campaign-b2-a2 slice's own wire type — one codec, one ID
 * vocabulary across the surface); null stays the honest null of a
 * schema-less legacy product. The declared closure is exactly the
 * bounded projection's own facts: {@code name} the display title, and
 * {@code status} the display lifecycle state name — a PLATFORM
 * EXTENSION fact the registry owns and the Saleor wire shape does not
 * carry, preserved per the governing matrix's own "platform-only fields
 * as extensions" policy. Unowned Saleor fields stay undeclared until an
 * owning capability exists — never faked.</p>
 */
@Component
public class ProductGraphQlMapper {

    /** The GraphQL type tag of the Saleor Product type — the codec's own contract. */
    static final String PRODUCT_TYPE = "Product";

    /** The GraphQL type tag of the Saleor ProductType type (the productType field's own wire type). */
    static final String PRODUCT_TYPE_REFERENCE = "ProductType";

    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one product projection onto the wire type.
     *
     * @param view the catalog owner's bounded product projection
     * @return the Saleor-shaped product projection (with its documented
     *         platform extension fact)
     */
    public Product toGraphQlProduct(ProductView view) {
        Product.Builder builder = Product.newBuilder()
                .id(globalIdCodec.encode(PRODUCT_TYPE, view.id()))
                .name(view.name())
                .status(view.status());
        if (view.productTypeId() != null) {
            builder.productType(globalIdCodec.encode(PRODUCT_TYPE_REFERENCE, view.productTypeId()));
        }
        return builder.build();
    }
}
