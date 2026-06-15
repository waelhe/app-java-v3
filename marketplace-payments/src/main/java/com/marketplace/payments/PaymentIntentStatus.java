package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum PaymentIntentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED;

    public static final java.util.Map<PaymentIntentStatus, Set<PaymentIntentStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    CREATED, EnumSet.of(PROCESSING, CANCELLED),
                    PROCESSING, EnumSet.of(SUCCEEDED, FAILED),
                    SUCCEEDED, EnumSet.of(REFUNDED, PARTIALLY_REFUNDED),
                    FAILED, EnumSet.noneOf(PaymentIntentStatus.class),
                    CANCELLED, EnumSet.noneOf(PaymentIntentStatus.class),
                    REFUNDED, EnumSet.noneOf(PaymentIntentStatus.class),
                    PARTIALLY_REFUNDED, EnumSet.noneOf(PaymentIntentStatus.class)
            ));

    public void validateTransitionTo(PaymentIntentStatus target) {
        Set<PaymentIntentStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new ConflictException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}
