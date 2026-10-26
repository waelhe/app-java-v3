package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

/** Order lifecycle seam used by delivery without importing marketplace-orders. */
public interface OrderDeliveryPort {
    OrderDeliveryDetails requireConfirmedOrderForDelivery(UUID orderId, UUID buyerId);
    void attachDeliveryRequest(UUID orderId, UUID buyerId, UUID deliveryRequestId);
    void clearDeliveryRequest(UUID orderId, UUID deliveryRequestId);
    void fulfillFromDelivery(UUID orderId, UUID deliveryRequestId);

    /**
     * The V224 checkout-integration read: the CONFIRMED order's frozen
     * fulfillment selection — the facts the delivery module consumes to
     * create the order's legs (the {@code OrderFulfillmentListener} on
     * {@code OrderConfirmedEvent}) and to guard the manual add-on quoting
     * path (a checkout-selected order never buys a second delivery).
     *
     * @throws com.marketplace.shared.api.ResourceNotFoundException when the
     *         order is absent or not the buyer's (the privacy contract)
     * @throws com.marketplace.shared.api.ConflictException when the order
     *         is not CONFIRMED or carries no seller
     */
    OrderFulfillmentSelection requireOrderFulfillment(UUID orderId, UUID buyerId);

    /**
     * Agent findings 2026-10-26T13:11 export, row 41 — the settlement
     * edge's resolution: the frozen fulfillment selection of the order a
     * settled payment intent pays for. Present only when that order is
     * CONFIRMED with a known seller (the leg's own creation
     * preconditions) — the absent answer covers every other shape: a
     * non-order intent (the empty findByPaymentIntentId), an order that
     * has not confirmed yet (the seller-first flow — this edge is NOT
     * the leg's creator while the confirmation edge still owes it), and
     * an order that moved past CONFIRMED (its legs are the cancellation
     * edges' concern, never a fresh creation). The absent row is the
     * honest no-op, never an exception — the events contract's
     * recoverable consumer.
     */
    Optional<OrderFulfillmentSelection> findOrderFulfillmentByPaymentIntent(UUID paymentIntentId);
}
