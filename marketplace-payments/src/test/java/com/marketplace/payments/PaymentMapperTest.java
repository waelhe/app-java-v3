package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * S7 guard: the refund surface's money shape — the payment's fields with the
 * intent's ISO 4217 currency composed in (the base-plus-overload shape of
 * {@code PaymentIntentMapper}'s clientSecret).
 */
class PaymentMapperTest {

    private final PaymentMapper mapper = Mappers.getMapper(PaymentMapper.class);

    @Test
    void toResponse_refundedPayment_composesTheIntentsCurrency() {
        UUID intentId = UUID.randomUUID();
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("evt_mapper");
        payment.markRefunded();
        PaymentIntent intent = PaymentIntent.create(intentId, UUID.randomUUID(), 5000L, "USD", null);

        PaymentResponse response = mapper.toResponse(new PaymentsService.RefundedPayment(payment, intent));

        assertEquals(payment.getId(), response.id());
        assertEquals(5000L, response.amountCents());
        assertEquals("USD", response.currency(), "the intent's currency rides the refund answer");
        assertEquals("REFUNDED", response.status());
    }

    @Test
    void toResponse_basePayment_leavesCurrencyAbsent() {
        Payment payment = Payment.create(UUID.randomUUID(), 5000L);

        PaymentResponse base = mapper.toResponse(payment);

        assertNull(base.currency(), "the base mapping carries no currency — the entity has none");
        assertEquals(5000L, base.amountCents());
    }
}
