package com.marketplace.shared.api;

import java.util.UUID;

/** Requests the existing payments owner to cancel/refund a failed delivery. */
public record DeliveryRefundRequestedEvent(UUID deliveryRequestId, String reason, UUID orderId) {
    public DeliveryRefundRequestedEvent(UUID deliveryRequestId, String reason) {
        this(deliveryRequestId, reason, null);
    }
}
