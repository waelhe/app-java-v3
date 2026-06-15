package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    public static final java.util.Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    PENDING, EnumSet.of(COMPLETED, FAILED),
                    COMPLETED, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED),
                    FAILED, EnumSet.noneOf(PaymentStatus.class),
                    REFUNDED, EnumSet.noneOf(PaymentStatus.class),
                    PARTIALLY_REFUNDED, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED)
            ));

    public void validateTransitionTo(PaymentStatus target) {
        Set<PaymentStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new ConflictException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}
