package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class PaymentIntentSpecificationsTest {

    @Test
    void hasBookingId_returnsSpec() {
        Specification<PaymentIntent> spec = PaymentIntentSpecifications.hasBookingId(UUID.randomUUID());
        assertNotNull(spec);
    }

    @Test
    void hasStatus_returnsSpec() {
        Specification<PaymentIntent> spec = PaymentIntentSpecifications.hasStatus(PaymentIntentStatus.CREATED);
        assertNotNull(spec);
    }
}
