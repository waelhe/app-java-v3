package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Collection;
import com.marketplace.catalog.spi.CollectionView;
import org.springframework.stereotype.Component;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collection}
 * and {@code collections} read roots, packet agent-2): the transport
 * mapping of the catalog owner's bounded collection projection onto the
 * Saleor-shaped {@code Collection} wire type. This class owns ONLY
 * transport mapping — the global-ID encode and the platform extension
 * facts' wire rendering — never a business rule (the same boundary
 * discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, the owner's
 * native {@code collections} key behind it. The declared closure is
 * exactly the bounded projection's own facts: {@code name} the rendered
 * primary display name, {@code slug} the row's stable public key, and
 * the PLATFORM EXTENSION facts the registry owns and the Saleor wire
 * shape does not carry on this bounded surface: {@code nameAr} (the
 * bilingual second name) and {@code isPublished} (the row's own
 * publication fact — the include-unpublished law's own wire fact) —
 * preserved per the governing matrix's own "platform-only fields as
 * extensions" policy. Unowned Saleor fields (the metadata family, SEO
 * fields, rich-text descriptions, externalReference, the channel
 * family, backgroundImage) stay undeclared until an owning capability
 * exists — never faked.</p>
 */
@Component
public class CollectionGraphQlMapper {

    /** The GraphQL type tag of the Saleor Collection type — the codec's own contract. */
    static final String COLLECTION_TYPE = "Collection";

    private final SaleorGlobalIdCodec globalIdCodec;

    public CollectionGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one collection projection onto the wire type.
     *
     * @param view the catalog owner's bounded collection projection
     * @return the Saleor-shaped collection projection (with its two
     *         documented platform extension facts)
     */
    public Collection toGraphQlCollection(CollectionView view) {
        return Collection.newBuilder()
                .id(globalIdCodec.encode(COLLECTION_TYPE, view.id()))
                .name(view.name())
                .slug(view.slug())
                .nameAr(view.nameAr())
                .isPublished(view.published())
                .build();
    }
}
