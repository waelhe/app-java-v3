package com.marketplace.payments;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        Long amountCents,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getAmountCents(),
                payment.getStatus().name(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
