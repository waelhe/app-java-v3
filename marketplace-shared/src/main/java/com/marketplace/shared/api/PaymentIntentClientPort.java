package com.marketplace.shared.api;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.UUID;

/** Owner-checked client payment operations exposed to transport adapters. */
@NamedInterface("payment-client")
public interface PaymentIntentClientPort {
    PaymentIntentClientView get(UUID paymentIntentId, Authentication authentication);
    PaymentIntentClientView process(UUID paymentIntentId, Authentication authentication);
    PaymentIntentClientView cancel(UUID paymentIntentId, Authentication authentication);

    record PaymentIntentClientView(
            UUID id,
            UUID bookingId,
            String amountMinor,
            String currency,
            String status,
            String pspIntentId,
            String clientSecret,
            Instant createdAt,
            Instant updatedAt) { }
}
