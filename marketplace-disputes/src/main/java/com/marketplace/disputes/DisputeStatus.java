package com.marketplace.disputes;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum DisputeStatus {
    OPEN,
    RESOLVED;

    public static final java.util.Map<DisputeStatus, Set<DisputeStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    OPEN, EnumSet.of(RESOLVED),
                    RESOLVED, EnumSet.noneOf(DisputeStatus.class)
            ));

    public void validateTransitionTo(DisputeStatus target) {
        Set<DisputeStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}
