package com.marketplace.provider;

import java.time.Instant;
import java.util.UUID;

/**
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): the claim's
 * wire view — the shape the claimant's own submission answer, the
 * administrative review answer, and the queue rows all share (the
 * {@code ProviderFulfillmentLocationResponse.from} house shape: a static
 * {@code of} over the entity, no mapper machinery for one record).
 */
public record ProviderClaimResponse(
        UUID id,
        UUID providerId,
        UUID claimantUserId,
        String contactChannel,
        String note,
        ProviderClaimState state,
        String decidedBy,
        String decisionNote,
        Instant decidedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProviderClaimResponse of(ProviderClaim claim) {
        return new ProviderClaimResponse(
                claim.getId(),
                claim.getProviderId(),
                claim.getClaimantUserId(),
                claim.getContactChannel(),
                claim.getNote(),
                claim.getState(),
                claim.getDecidedBy(),
                claim.getDecisionNote(),
                claim.getDecidedAt(),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }
}
