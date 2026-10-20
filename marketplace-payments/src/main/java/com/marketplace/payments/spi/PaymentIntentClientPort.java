package com.marketplace.payments.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.UUID;

/**
 * Owner-checked client payment operations exposed to transport adapters.
 *
 * <p><b>Placement (the ArchitectureRulesTest verdict, the ProductAdminSpi
 * pattern):</b> the seam carries Spring Security's {@code Authentication}, so
 * it lives in the OWNING module's {@code spi} package — {@code shared.api} is
 * the framework-neutral zone by the #429 build gate.
 */
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
