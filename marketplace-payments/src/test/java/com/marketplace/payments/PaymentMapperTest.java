package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentMapperTest {

    private final PaymentMapper mapper = Mappers.getMapper(PaymentMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        Payment payment = Payment.create(UUID.randomUUID(), 5000L);

        PaymentResponse response = mapper.toResponse(payment);

        assertEquals(payment.getId(), response.id());
        assertEquals(payment.getAmountCents(), response.amountCents());
        assertEquals(payment.getStatus().name(), response.status());
    }
}
