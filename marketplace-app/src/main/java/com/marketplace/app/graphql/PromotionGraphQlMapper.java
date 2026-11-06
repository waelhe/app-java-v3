package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Promotion;
import com.marketplace.app.graphql.generated.types.PromotionTypeEnum;
import com.marketplace.pricing.spi.PromotionView;
import org.springframework.stereotype.Component;

/**
 * P3 (the canonical Saleor plan §5-P3): the transport mapping of the
 * pricing owner's bounded promotion projection onto the Saleor-shaped
 * {@code Promotion} wire type. This class owns ONLY transport mapping —
 * the global-ID encode and the ISO-8601 instant rendering — never a
 * business rule (the same boundary discipline every house mapper
 * follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits — opaque on the wire, the owner's
 * native {@code promotions} key behind it. The declared closure is
 * exactly the bounded projection's own facts: {@code name} (the policy
 * row's own name), {@code type} (the owner's application level — ORDER,
 * the platform's promotions are order-level policies by the entity's
 * own contract), and the window/audit instants rendered as ISO-8601
 * strings (the house no-new-scalar decision the User.dateJoined/
 * NeighborhoodPost.createdAt declarations established — Saleor's
 * DateTime scalar divergence row). Unowned Saleor fields — the rules
 * array with its channel-scoped reward values, the metadata family,
 * translations and the event log — stay undeclared until an owning
 * capability exists: never faked.</p>
 */
@Component
public class PromotionGraphQlMapper {

    /** The GraphQL type tag of the Saleor Promotion type — the codec's own contract. */
    static final String PROMOTION_TYPE = "Promotion";

    private final SaleorGlobalIdCodec globalIdCodec;

    public PromotionGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one promotion projection onto the wire type.
     *
     * @param view the pricing owner's bounded promotion projection
     * @return the Saleor-shaped promotion projection
     */
    public Promotion toGraphQlPromotion(PromotionView view) {
        return Promotion.newBuilder()
                .id(globalIdCodec.encode(PROMOTION_TYPE, view.id()))
                .name(view.name())
                .type(view.orderLevel() ? PromotionTypeEnum.ORDER : PromotionTypeEnum.CATALOGUE)
                .startDate(view.startsAt() == null ? null : view.startsAt().toString())
                .endDate(view.endsAt() == null ? null : view.endsAt().toString())
                .createdAt(view.createdAt().toString())
                .updatedAt(view.updatedAt().toString())
                .build();
    }
}
