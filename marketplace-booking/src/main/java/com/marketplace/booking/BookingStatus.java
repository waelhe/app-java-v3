package com.marketplace.booking;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum BookingStatus {
    PENDING,
    CONFIRMED,
    COMPLETED,
    CANCELLED;

    public static final java.util.Map<BookingStatus, Set<BookingStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    PENDING, EnumSet.of(CONFIRMED, CANCELLED),
                    CONFIRMED, EnumSet.of(COMPLETED, CANCELLED),
                    COMPLETED, EnumSet.noneOf(BookingStatus.class),
                    CANCELLED, EnumSet.noneOf(BookingStatus.class)
            ));

    public void validateTransitionTo(BookingStatus target) {
        Set<BookingStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}