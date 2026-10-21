package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Attribute;
import com.marketplace.app.graphql.generated.types.AttributeInputTypeEnum;
import com.marketplace.catalog.CatalogAttributeType;
import com.marketplace.catalog.spi.AttributeView;
import org.springframework.stereotype.Component;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code attribute} and
 * {@code attributes} read roots, the declared continuation of the
 * campaign-b2-a2 product-type slice): the transport mapping of the catalog
 * owner's bounded attribute projection onto the Saleor-shaped
 * {@code Attribute} wire type. This class owns ONLY transport mapping —
 * the global-ID encode and the Saleor vocabulary renderings of the
 * domain's closed value shape — never a business rule (the same boundary
 * discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, the owner's
 * native {@code catalog_attribute_definitions} key behind it. The declared
 * closure is exactly the bounded projection's own facts: {@code name} the
 * rendered primary display name, {@code slug} the row's immutable public
 * code, {@code inputType} the Saleor rendering of the closed domain value
 * shape (TEXT→PLAIN_TEXT, NUMBER→NUMERIC, BOOLEAN→BOOLEAN,
 * CHOICE→DROPDOWN — the platform's free text is plain text, never rich
 * text, and its CHOICE holds exactly one of the registered options, never
 * a multiselect), and {@code withChoices} the CHOICE-typed fact (the
 * definition's values are its registered options). Unowned Saleor fields
 * stay undeclared until an owning capability exists — never faked.</p>
 */
@Component
public class AttributeGraphQlMapper {

    /** The GraphQL type tag of the Saleor Attribute type — the codec's own contract. */
    static final String ATTRIBUTE_TYPE = "Attribute";

    private final SaleorGlobalIdCodec globalIdCodec;

    public AttributeGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one attribute projection onto the wire type.
     *
     * @param view the catalog owner's bounded attribute projection
     * @return the Saleor-shaped attribute projection
     */
    public Attribute toGraphQlAttribute(AttributeView view) {
        return Attribute.newBuilder()
                .id(globalIdCodec.encode(ATTRIBUTE_TYPE, view.id()))
                .name(view.name())
                .slug(view.slug())
                .inputType(toInputType(view.valueType()))
                .withChoices(view.valueType() == CatalogAttributeType.CHOICE)
                .build();
    }

    /**
     * The closed domain value shape's Saleor rendering — every vocabulary
     * value maps (the enum is closed), so the mapping is total and never
     * null: the domain {@code valueType} column is NOT NULL and the Saleor
     * {@code inputType} is its honest widget vocabulary.
     */
    private static AttributeInputTypeEnum toInputType(CatalogAttributeType valueType) {
        return switch (valueType) {
            case TEXT -> AttributeInputTypeEnum.PLAIN_TEXT;
            case NUMBER -> AttributeInputTypeEnum.NUMERIC;
            case BOOLEAN -> AttributeInputTypeEnum.BOOLEAN;
            case CHOICE -> AttributeInputTypeEnum.DROPDOWN;
        };
    }
}
