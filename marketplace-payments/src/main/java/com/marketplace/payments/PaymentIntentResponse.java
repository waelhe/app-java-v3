package com.marketplace.payments;

import java.time.Instant;
import java.util.UUID;

public record PaymentIntentResponse(
        UUID id,
        UUID bookingId,
        Long amountCents,
        String currency,
        String status,
        String pspIntentId,
        String clientSecret,
        Instant createdAt,
        Instant updatedAt
) {
}
