package com.marketplace.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10) — the wire shape of one verification
 * attestation (the entity never crosses the HTTP boundary — the
 * {@code FollowView} shape verbatim). The trust type carries the closed
 * §6.5 vocabulary NAME (the shared-api String-vocabulary rule).
 */
public record VerificationAttestationView(
        UUID id,
        UUID subjectUserId,
        String trustType,
        String state,
        String evidenceRef,
        Instant grantedAt,
        Instant revokedAt,
        UUID grantedBy
) {

    static VerificationAttestationView of(VerificationAttestation attestation) {
        return new VerificationAttestationView(
                attestation.getId(),
                attestation.getSubjectUserId(),
                attestation.getTrustType().name(),
                attestation.getState().name(),
                attestation.getEvidenceRef(),
                attestation.getGrantedAt(),
                attestation.getRevokedAt(),
                attestation.getGrantedBy());
    }
}
