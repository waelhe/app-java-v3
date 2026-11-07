package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Order;
import com.marketplace.app.graphql.generated.types.OrderStatus;
import com.marketplace.orders.spi.OrderView;
import org.springframework.stereotype.Component;

/**
 * P4 (the canonical Saleor plan §5-P4 — the Saleor {@code order} and
 * {@code orders} read roots, packet campaign-b5-b): the transport
 * mapping of the orders owner's bounded order projection onto the
 * Saleor-shaped {@code Order} wire type. This class owns ONLY
 * transport mapping — the global-ID encode, the lifecycle vocabulary's
 * wire rendering and the temporal serialization — never a business
 * rule (the same boundary discipline every house mapper follows).
 *
 * <p>The {@code id} is the typed Relay-style global ID the
 * {@link SaleorGlobalIdCodec} emits (type tag {@code Order} — opaque
 * on the wire, the owner's native {@code orders} key behind it). The
 * {@code created} field renders the placement instant as the ISO-8601
 * string the house's deliberate no-new-scalar decision carries on the
 * whole surface (the locked Boot 4.1.1 -> spring-graphql 2.0.5 ->
 * graphql-java 25.0 chain adds no temporal scalar — the recorded
 * divergence from upstream's DateTime scalar). The {@code status}
 * field renders the platform's own lifecycle state through Saleor's
 * own OrderStatus vocabulary by the registered ADAPT mapping — the
 * platform machine's states map one-to-one onto upstream's: PLACED
 * (placed, not yet confirmed) renders UNCONFIRMED, CONFIRMED renders
 * UNFULFILLED, FULFILLED renders FULFILLED, CANCELLED renders
 * CANCELED (the spelling is upstream's own); the other five upstream
 * values stay inert SDL vocabulary this platform never emits. The
 * declared closure is exactly the bounded projection's own facts;
 * unowned Saleor fields stay undeclared until an owning capability
 * exists — never faked.</p>
 */
@Component
public class OrderGraphQlMapper {

    /** The GraphQL type tag of the Saleor Order type — the codec's own contract. */
    static final String ORDER_TYPE = "Order";

    private final SaleorGlobalIdCodec globalIdCodec;

    public OrderGraphQlMapper(SaleorGlobalIdCodec globalIdCodec) {
        this.globalIdCodec = globalIdCodec;
    }

    /**
     * Maps one order projection onto the wire type.
     *
     * @param view the orders owner's bounded order projection
     * @return the Saleor-shaped order projection
     */
    public Order toGraphQlOrder(OrderView view) {
        return Order.newBuilder()
                .id(globalIdCodec.encode(ORDER_TYPE, view.id()))
                .created(view.placedAt().toString())
                .status(saleorStatusOf(view.status()))
                .build();
    }

    /**
     * The lifecycle vocabulary's ADAPT mapping — the platform machine's
     * states onto Saleor's own OrderStatus vocabulary (the registered
     * divergence row): PLACED renders UNCONFIRMED, CONFIRMED renders
     * UNFULFILLED, FULFILLED and CANCELLED render their upstream
     * spellings. An unknown platform state never happens (the closed
     * enum is the V113 CHECK's own membership set) — the defensive leg
     * answers the boundary's classified validation family, never a
     * fabricated status.
     */
    private static OrderStatus saleorStatusOf(String platformStatus) {
        return switch (platformStatus) {
            case "PLACED" -> OrderStatus.UNCONFIRMED;
            case "CONFIRMED" -> OrderStatus.UNFULFILLED;
            case "FULFILLED" -> OrderStatus.FULFILLED;
            case "CANCELLED" -> OrderStatus.CANCELED;
            default -> throw new IllegalArgumentException(
                    "Unknown platform order status: " + platformStatus);
        };
    }
}
