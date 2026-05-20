package com.marketplace.provider;
import com.marketplace.shared.api.ConflictException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ProviderStatus {
    PENDING,
    VERIFIED,
    SUSPENDED;

    public static final java.util.Map<ProviderStatus, Set<ProviderStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    PENDING, EnumSet.of(VERIFIED, SUSPENDED),
                    VERIFIED, EnumSet.of(SUSPENDED),
                    SUSPENDED, EnumSet.of(VERIFIED)
            ));

    public void validateTransitionTo(ProviderStatus target) {
        Set<ProviderStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new ConflictException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}
