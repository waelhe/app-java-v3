package com.marketplace.payments;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaymentIntentResponseTest {

    @Test
    void fromMapsPaymentIntent() {
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, "key");

        var response = PaymentIntentResponse.from(intent);

        assertEquals(intent.getId(), response.id());
        assertEquals(intent.getBookingId(), response.bookingId());
        assertEquals(5000L, response.amountCents());
        assertEquals("SAR", response.currency());
        assertEquals(PaymentIntentStatus.CREATED.name(), response.status());
        assertEquals(intent.getCreatedAt(), response.createdAt());
        assertEquals(intent.getUpdatedAt(), response.updatedAt());
    }
}
