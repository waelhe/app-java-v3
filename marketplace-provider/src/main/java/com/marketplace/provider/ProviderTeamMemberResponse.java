package com.marketplace.provider;

import java.time.Instant;
import java.util.UUID;

/**
 * W5b (the journeys register's J29 row): the delegated seat's wire form —
 * the owner-facing roster entry. Carries the delegation's own facts (the
 * member, the role class, the live status, the explicit attribution) and
 * deliberately NOT the auditing columns: {@code addedBy} IS the
 * delegation's honest WHO (the acting owner), while {@code createdBy}
 * stays an infrastructure concern of the storage layer.
 */
public record ProviderTeamMemberResponse(
        UUID id,
        UUID memberUserId,
        ProviderTeamRole role,
        ProviderTeamMemberStatus status,
        String addedBy,
        Instant createdAt) {

    static ProviderTeamMemberResponse from(ProviderTeamMember member) {
        return new ProviderTeamMemberResponse(member.getId(), member.getMemberUserId(),
                member.getRole(), member.getStatus(), member.getAddedBy(), member.getCreatedAt());
    }
}
