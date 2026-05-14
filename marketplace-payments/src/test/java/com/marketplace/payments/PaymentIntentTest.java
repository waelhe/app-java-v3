package com.marketplace.payments;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentIntentTest {

    @Test
    void createSetsCreatedStatus() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, "idem-1");
        assertNotNull(intent.getId());
        assertEquals(PaymentIntentStatus.CREATED, intent.getStatus());
        assertEquals("idem-1", intent.getIdempotencyKey());
    }

    @Test
    void markProcessingSetsStatus() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        assertEquals(PaymentIntentStatus.PROCESSING, intent.getStatus());
    }

    @Test
    void markProcessingThrowsWhenNotCreated() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        assertThrows(IllegalStateException.class, intent::markProcessing);
    }

    @Test
    void markSucceededSetsStatus() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
    }

    @Test
    void markSucceededThrowsWhenNotProcessing() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        assertThrows(IllegalStateException.class, intent::markSucceeded);
    }

    @Test
    void markFailedSetsStatus() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markFailed();
        assertEquals(PaymentIntentStatus.FAILED, intent.getStatus());
    }

    @Test
    void markFailedThrowsWhenNotProcessing() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        assertThrows(IllegalStateException.class, intent::markFailed);
    }

    @Test
    void cancelSetsStatus() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.cancel();
        assertEquals(PaymentIntentStatus.CANCELLED, intent.getStatus());
    }

    @Test
    void cancelThrowsWhenSucceeded() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        assertThrows(IllegalStateException.class, intent::cancel);
    }

    @Test
    void constructorSetsFields() {
        var id = UUID.randomUUID();
        var bookingId = UUID.randomUUID();
        var consumerId = UUID.randomUUID();
        var intent = new PaymentIntent(id, bookingId, consumerId, 3000L, "key");
        assertEquals(id, intent.getId());
        assertEquals(bookingId, intent.getBookingId());
        assertEquals(consumerId, intent.getConsumerId());
        assertEquals(3000L, intent.getAmountCents());
        assertEquals("SAR", intent.getCurrency());
        assertEquals("key", intent.getIdempotencyKey());
        assertEquals(PaymentIntentStatus.CREATED, intent.getStatus());
    }
}
