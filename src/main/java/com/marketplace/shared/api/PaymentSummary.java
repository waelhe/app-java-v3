package com.marketplace.shared.api;

import com.marketplace.payments.PaymentIntentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentSummary(
        UUID id,
        UUID bookingId,
        UUID consumerId,
        Long amountCents,
        String currency,
        PaymentIntentStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
