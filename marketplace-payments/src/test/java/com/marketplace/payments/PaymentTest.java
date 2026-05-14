package com.marketplace.payments;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentTest {

    @Test
    void createSetsPending() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);
        assertNotNull(payment.getId());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
    }

    @Test
    void markCompletedSetsStatusAndExternalId() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);
        payment.markCompleted("ext-123");
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals("ext-123", payment.getExternalId());
    }

    @Test
    void markCompletedThrowsWhenNotPending() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);
        payment.markCompleted("ext-1");
        assertThrows(IllegalStateException.class, () -> payment.markCompleted("ext-2"));
    }

    @Test
    void markFailedSetsStatus() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);
        payment.markFailed();
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
    }

    @Test
    void markFailedThrowsWhenNotPending() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);
        payment.markCompleted("ext");
        assertThrows(IllegalStateException.class, payment::markFailed);
    }

    @Test
    void markRefundedSetsStatus() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);
        payment.markCompleted("ext");
        payment.markRefunded();
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    @Test
    void markRefundedThrowsWhenNotCompleted() {
        var payment = Payment.create(UUID.randomUUID(), 5000L);
        assertThrows(IllegalStateException.class, payment::markRefunded);
    }

    @Test
    void constructorSetsFields() {
        var id = UUID.randomUUID();
        var intentId = UUID.randomUUID();
        var payment = new Payment(id, intentId, 3000L);
        assertEquals(id, payment.getId());
        assertEquals(intentId, payment.getPaymentIntentId());
        assertEquals(3000L, payment.getAmountCents());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertNull(payment.getExternalId());
    }
}
