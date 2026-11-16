package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/** Committed physical-delivery completion fact, consumed by ledger and order fulfillment. */
public record DeliveryCompletedEvent(
        UUID deliveryRequestId,
        UUID paymentIntentId,
        long amountMinor,
        String currency,
        long courierCompensationMinor,
        Instant deliveredAt,
        UUID courierUserId,
        UUID orderId) {
    public DeliveryCompletedEvent(UUID deliveryRequestId, UUID paymentIntentId, long amountMinor,
                                  String currency, long courierCompensationMinor,
                                  Instant deliveredAt, UUID courierUserId) {
        this(deliveryRequestId, paymentIntentId, amountMinor, currency, courierCompensationMinor,
                deliveredAt, courierUserId, null);
    }

    public DeliveryCompletedEvent(UUID deliveryRequestId, UUID paymentIntentId, long amountMinor,
                                  String currency, Instant deliveredAt) {
        this(deliveryRequestId, paymentIntentId, amountMinor, currency, 0L, deliveredAt, null, null);
    }
}
