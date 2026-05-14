package com.marketplace.payments;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentResponseTest {

    @Test
    void fromMapsPayment() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);

        var response = PaymentResponse.from(payment);

        assertEquals(payment.getId(), response.id());
        assertEquals(5000L, response.amountCents());
        assertEquals(PaymentStatus.PENDING.name(), response.status());
        assertEquals(payment.getCreatedAt(), response.createdAt());
        assertEquals(payment.getUpdatedAt(), response.updatedAt());
    }
}
