package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Category;
import com.marketplace.catalog.spi.CategoryView;
import org.springframework.stereotype.Component;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code category} and
 * {@code categories} read roots, packet campaign-b4-a): the transport
 * mapping of the catalog owner's bounded category projection onto the
 * Saleor-shaped {@code Category} wire type. This class owns ONLY
 * transport mapping — the global-ID encode and the platform extension
 * facts' wire rendering — never a business rule (the same boundary
 * discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, the owner's
 * native {@code categories} key behind it. The declared closure is
 * exactly the bounded projection's own facts: {@code name} the rendered
 * primary display name (nullable — the registry's own no-constraint
 * nullability divergence), {@code slug} the row's immutable public code
 * (the code-as-slug mapping the attribute slice recorded), and the two
 * PLATFORM EXTENSION fields the registry owns and the Saleor wire shape
 * does not carry: {@code nameAr} (the bilingual second name) and
 * {@code position} (the registry's own display order) — preserved per
 * the governing matrix's own "platform-only fields as extensions"
 * policy. Unowned Saleor fields stay undeclared until an owning
 * capability exists — never faked.</p>
 */
@Component
public class CategoryGraphQlMapper {

    /** The GraphQL type tag of the Saleor Category type — the codec's own contract. */
    static final String CATEGORY_TYPE = "Category";

    private final SaleorGlobalIdCodec globalIdCodec;

    public CategoryGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one category projection onto the wire type.
     *
     * @param view the catalog owner's bounded category projection
     * @return the Saleor-shaped category projection (with its two
     *         documented platform extension facts)
     */
    public Category toGraphQlCategory(CategoryView view) {
        return Category.newBuilder()
                .id(globalIdCodec.encode(CATEGORY_TYPE, view.id()))
                .name(view.name())
                .slug(view.slug())
                .nameAr(view.nameAr())
                .position(view.position())
                .build();
    }
}
