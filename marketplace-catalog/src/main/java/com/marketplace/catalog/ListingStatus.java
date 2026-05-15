package com.marketplace.catalog;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ListingStatus {
    DRAFT,
    ACTIVE,
    PAUSED,
    ARCHIVED;

    public static final java.util.Map<ListingStatus, Set<ListingStatus>> TRANSITIONS =
            Collections.unmodifiableMap(java.util.Map.of(
                    DRAFT, EnumSet.of(ACTIVE, ARCHIVED),
                    ACTIVE, EnumSet.of(PAUSED, ARCHIVED),
                    PAUSED, EnumSet.of(ACTIVE, ARCHIVED),
                    ARCHIVED, EnumSet.noneOf(ListingStatus.class)
            ));

    public void validateTransitionTo(ListingStatus target) {
        Set<ListingStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalStateException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}