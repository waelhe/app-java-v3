package com.marketplace.payments.spi;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the payment-client port's projection record — the accessor-contract
 * pin that moved with the seam from shared.api to the owning module's spi
 * package (the framework-neutral zone verdict).
 */
class PaymentIntentClientPortRecordContractTest {

    @Test
    void paymentIntentClientProjectionExposesEveryContractField() {
        UUID id = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Instant created = Instant.parse("2026-10-20T10:00:00Z");
        Instant updated = created.plusSeconds(60);
        var intent = new PaymentIntentClientPort.PaymentIntentClientView(
                id, bookingId, "3000", "SAR", "CREATED", "psp-1", "client-secret", created, updated);
        assertThat(intent.id()).isEqualTo(id);
        assertThat(intent.bookingId()).isEqualTo(bookingId);
        assertThat(intent.amountMinor()).isEqualTo("3000");
        assertThat(intent.currency()).isEqualTo("SAR");
        assertThat(intent.status()).isEqualTo("CREATED");
        assertThat(intent.pspIntentId()).isEqualTo("psp-1");
        assertThat(intent.clientSecret()).isEqualTo("client-secret");
        assertThat(intent.createdAt()).isEqualTo(created);
        assertThat(intent.updatedAt()).isEqualTo(updated);
    }
}
