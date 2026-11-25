package com.marketplace.orders.spi;

import com.marketplace.orders.FulfillmentMethod;
import com.marketplace.orders.Order;
import com.marketplace.orders.OrderRepository;
import com.marketplace.shared.api.OrderSellerPort;
import com.marketplace.shared.api.OrderSettlementFacts;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Stage 6 (ADR-0002): the orders module's implementation of the
 * {@link OrderSellerPort} cross-module contract — the facts the
 * ledger's ORDER-origin settlement needs. The seller is
 * stored on the order at placement (the single-seller invariant), so the
 * read is one own-table hop — no cross-module traversal here either.
 *
 * <p><b>A1 (owner-gate batch 6a — the ADR-0013 D4 execution):</b> the
 * widened {@code settlementFactsOf} read carries the frozen fulfillment
 * split from the same one-hop row: the {@code shippingAmountMinor} the
 * checkout placement factory froze beside the total (present exactly
 * when the method is PLATFORM_DELIVERY, and already inside
 * {@code totalAmountMinor}), and the method flag itself. PICKUP and
 * legacy orders carry the zero-fee shape (the V224 placement law).</p>
 */
@Component
public class OrderSellerAdapter implements OrderSellerPort {

    private final OrderRepository orderRepository;

    public OrderSellerAdapter(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UUID sellerOf(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
                    if (order.getSellerId() == null) {
                        // Legacy orders predate the ADR-0002 invariant; their
                        // settlement is not attributable, and inventing a seller
                        // would corrupt the ledger — the honest 404 keeps the
                        // books clean.
                        throw new ResourceNotFoundException("Order seller", orderId);
                    }
                    return order.getSellerId();
                })
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }

    /**
     * A1 (ADR-0013 D4): the settlement facts — the same one-hop row the
     * {@link #sellerOf(UUID)} read walks, carrying the frozen fee split.
     * The seller's non-attributable legacy shape answers the same honest
     * 404 (inventing a seller would corrupt the ledger; a fee-bearing
     * seller-less row cannot exist — the placement invariant stores both).
     */
    @Override
    @Transactional(readOnly = true)
    public OrderSettlementFacts settlementFactsOf(UUID orderId) {
        return orderRepository.findById(orderId)
                .map(order -> {
                    if (order.getSellerId() == null) {
                        throw new ResourceNotFoundException("Order seller", orderId);
                    }
                    boolean platformDelivery =
                            order.getFulfillmentMethod() == FulfillmentMethod.PLATFORM_DELIVERY;
                    long shipping = platformDelivery && order.getShippingAmountMinor() != null
                            ? order.getShippingAmountMinor()
                            : 0L;
                    return new OrderSettlementFacts(order.getSellerId(), shipping, platformDelivery);
                })
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
    }
}
