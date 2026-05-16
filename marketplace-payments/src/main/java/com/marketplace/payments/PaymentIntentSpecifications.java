package com.marketplace.payments;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public final class PaymentIntentSpecifications {

    private PaymentIntentSpecifications() {}

    public static Specification<PaymentIntent> hasBookingId(UUID bookingId) {
        return (root, query, cb) -> cb.equal(root.get("bookingId"), bookingId);
    }

    public static Specification<PaymentIntent> hasStatus(PaymentIntentStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

}
