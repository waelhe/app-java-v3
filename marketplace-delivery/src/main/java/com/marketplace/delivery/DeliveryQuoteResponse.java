package com.marketplace.delivery;

import java.time.Instant;
import java.util.UUID;

public record DeliveryQuoteResponse(
        UUID id,
        UUID orderId,
        long distanceMeters,
        long estimatedDurationSeconds,
        long amountMinor,
        long courierCompensationMinor,
        String currency,
        Instant expiresAt) {

    static DeliveryQuoteResponse from(DeliveryQuote quote) {
        return new DeliveryQuoteResponse(quote.getId(), quote.getOrderId(), quote.getDistanceMeters(),
                quote.getRouteDurationSeconds(), quote.getAmountMinor(),
                quote.getCourierCompensationMinor(), quote.getCurrency(), quote.getExpiresAt());
    }
}
