package com.marketplace.provider;

import com.marketplace.shared.api.ConflictException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * W5b (the journeys register's J29 row): the delegation's own lifecycle —
 * the {@code ProviderStatus} shape applied to a team seat. Two states only:
 * ACTIVE (the authority holds) and REVOKED (the owner withdrew it — the
 * single exit; the row and its Envers trail stay, the urgent-alert
 * withdrawal shape).
 */
public enum ProviderTeamMemberStatus {
    ACTIVE,
    REVOKED;

    public static final Map<ProviderTeamMemberStatus, Set<ProviderTeamMemberStatus>> TRANSITIONS =
            Collections.unmodifiableMap(Map.of(
                    ACTIVE, EnumSet.of(REVOKED),
                    REVOKED, EnumSet.noneOf(ProviderTeamMemberStatus.class)
            ));

    public void validateTransitionTo(ProviderTeamMemberStatus target) {
        Set<ProviderTeamMemberStatus> allowed = TRANSITIONS.get(this);
        if (allowed == null || !allowed.contains(target)) {
            throw new ConflictException(
                    "Cannot transition from " + this + " to " + target
            );
        }
    }
}
