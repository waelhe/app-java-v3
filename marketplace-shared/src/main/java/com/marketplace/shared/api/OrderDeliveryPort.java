package com.marketplace.shared.api;

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
     * Part-2 review round 2 — the SAME frozen selection read under the
     * order row's write lock: the LEG-CREATION surfaces (the split
     * command and the confirmation listener) serialize with the direct
     * fulfillment gate on the order row itself, so a leg can no longer
     * appear between the gate's active-legs count and its commit. The
     * manual quoting path keeps the lock-free read — a quote creates
     * no leg.
     *
     * @throws com.marketplace.shared.api.ResourceNotFoundException when the
     *         order is absent or not the buyer's (the privacy contract)
     * @throws com.marketplace.shared.api.ConflictException when the order
     *         is not CONFIRMED or carries no seller (the moved-past truth
     *         a serialized reader now sees)
     */
    OrderFulfillmentSelection requireOrderFulfillmentForUpdate(UUID orderId, UUID buyerId);
}
