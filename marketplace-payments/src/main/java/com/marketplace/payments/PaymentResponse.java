package com.marketplace.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * S7 (platform-readiness audit §5 — the shape row): every response that
 * carries a monetary amount carries its ISO 4217 currency too. The refund
 * surface answers the payment's amount in the currency of the intent that
 * created it — a client never formats a bare number.
 */
public record PaymentResponse(
        UUID id,
        Long amountCents,
        String currency,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
