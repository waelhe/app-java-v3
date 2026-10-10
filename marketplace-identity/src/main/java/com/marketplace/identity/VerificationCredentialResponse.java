package com.marketplace.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The read shape of a verification credential (ADR-0001, plan §Phase 1) —
 * stored names for the enums (the house String-vocabulary precedent), the
 * full review columns for the admin queue, the same record for the /me
 * surface (the account reads its own history with the same honesty the
 * admin queue gets).
 */
public record VerificationCredentialResponse(
        UUID id,
        UUID userId,
        String credentialType,
        String status,
        String evidenceUri,
        String notes,
        String decisionNotes,
        String reviewedBy,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static VerificationCredentialResponse from(VerificationCredential credential) {
        return new VerificationCredentialResponse(
                credential.getId(),
                credential.getUserId(),
                credential.getCredentialType().name(),
                credential.getStatus().name(),
                credential.getEvidenceUri(),
                credential.getNotes(),
                credential.getDecisionNotes(),
                credential.getReviewedBy(),
                credential.getReviewedAt(),
                credential.getCreatedAt(),
                credential.getUpdatedAt());
    }
}
