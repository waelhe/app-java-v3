package com.marketplace.payments;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED;

    public static final java.util.Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    PENDING, EnumSet.of(COMPLETED, FAILED),
                    COMPLETED, EnumSet.of(REFUNDED),
                    FAILED, EnumSet.noneOf(PaymentStatus.class),
                    REFUNDED, EnumSet.noneOf(PaymentStatus.class)
            ));

    public void validateTransitionTo(PaymentStatus target) {
        Set<PaymentStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}