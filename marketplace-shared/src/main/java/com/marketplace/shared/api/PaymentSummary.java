package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentSummary(
        UUID id,
        UUID bookingId,
        UUID consumerId,
        Long amountCents,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
