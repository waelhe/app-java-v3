package com.marketplace.orders.spi;

import com.marketplace.orders.OrderRepository;
import com.marketplace.shared.api.OrderSellerPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Stage 6 (ADR-0002): the orders module's implementation of the
 * {@link OrderSellerPort} cross-module contract — the single fact the
 * ledger's ORDER-origin settlement needs (who to credit). The seller is
 * stored on the order at placement (the single-seller invariant), so the
 * read is one own-table hop — no cross-module traversal here either.
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
}
