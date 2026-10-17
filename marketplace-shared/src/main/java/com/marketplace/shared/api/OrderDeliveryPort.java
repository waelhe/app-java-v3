package com.marketplace.shared.api;

import java.util.UUID;

/** Order lifecycle seam used by delivery without importing marketplace-orders. */
public interface OrderDeliveryPort {
    OrderDeliveryDetails requireConfirmedOrderForDelivery(UUID orderId, UUID buyerId);
    void attachDeliveryRequest(UUID orderId, UUID buyerId, UUID deliveryRequestId);
    void clearDeliveryRequest(UUID orderId, UUID deliveryRequestId);
    void fulfillFromDelivery(UUID orderId, UUID deliveryRequestId);
}
