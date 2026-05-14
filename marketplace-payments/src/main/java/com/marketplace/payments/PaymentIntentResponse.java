package com.marketplace.payments;

import java.time.Instant;
import java.util.UUID;

public record PaymentIntentResponse(
        UUID id,
        UUID bookingId,
        Long amountCents,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
                intent.getId(),
                intent.getBookingId(),
                intent.getAmountCents(),
                intent.getCurrency(),
                intent.getStatus().name(),
                intent.getCreatedAt(),
                intent.getUpdatedAt()
        );
    }
}
