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
}
