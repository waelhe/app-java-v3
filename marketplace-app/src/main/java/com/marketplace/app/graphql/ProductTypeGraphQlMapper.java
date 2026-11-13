package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.ProductType;
import com.marketplace.catalog.spi.ProductTypeView;
import org.springframework.stereotype.Component;

/**
 * P2 (the canonical Saleor plan §5-P2): the transport mapping of the
 * catalog owner's bounded product-type projection onto the Saleor-shaped
 * {@code ProductType} wire type. This class owns ONLY transport mapping —
 * the global-ID encode — never a business rule (the same boundary
 * discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, the owner's
 * native {@code product_types} key behind it. The declared closure is
 * exactly the bounded projection's own facts ({@code name} the rendered
 * primary display name, {@code slug} the row's immutable public code);
 * unowned Saleor fields stay undeclared until an owning capability exists
 * — never faked.</p>
 */
@Component
public class ProductTypeGraphQlMapper {

    /** The GraphQL type tag of the Saleor ProductType type — the codec's own contract. */
    static final String PRODUCT_TYPE_TYPE = "ProductType";

    private final SaleorGlobalIdCodec globalIdCodec;

    public ProductTypeGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one product-type projection onto the wire type.
     *
     * @param view the catalog owner's bounded product-type projection
     * @return the Saleor-shaped product-type projection
     */
    public ProductType toGraphQlProductType(ProductTypeView view) {
        return ProductType.newBuilder()
                .id(globalIdCodec.encode(PRODUCT_TYPE_TYPE, view.id()))
                .name(view.name())
                .slug(view.slug())
                .build();
    }
}
