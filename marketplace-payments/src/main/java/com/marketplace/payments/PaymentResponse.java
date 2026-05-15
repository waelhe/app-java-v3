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
}
