package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaymentIntentMapperTest {

    private final PaymentIntentMapper mapper = Mappers.getMapper(PaymentIntentMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        PaymentIntent intent = new PaymentIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, "key-1");

        PaymentIntentResponse response = mapper.toResponse(intent);

        assertEquals(intent.getId(), response.id());
        assertEquals(intent.getBookingId(), response.bookingId());
        assertEquals(intent.getAmountCents(), response.amountCents());
        assertEquals(intent.getCurrency(), response.currency());
        assertEquals(intent.getStatus().name(), response.status());
    }
}
