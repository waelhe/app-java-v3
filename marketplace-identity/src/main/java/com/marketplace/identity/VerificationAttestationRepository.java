package com.marketplace.identity;

import com.marketplace.shared.api.TrustType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10) — the verification attestation's own
 * repository. Every derived query rides Hibernate's {@code @SoftDelete}
 * filter (the {@code FollowRepository} rationale verbatim); the state
 * predicates ride the derived method names — the GRANTED set these
 * queries answer is exactly what V184's partial unique index protects
 * and what the future trust/provenance consumers (§4.5) will read.
 */
public interface VerificationAttestationRepository
        extends JpaRepository<VerificationAttestation, UUID> {

    /**
     * The (subject, type) attestation in ONE state — the GRANTED
     * duplicate's gate (the clean 409 before the review writes a second
     * granted fact) and the state lookups the transitions need.
     */
    Optional<VerificationAttestation> findBySubjectUserIdAndTrustTypeAndState(
            UUID subjectUserId, TrustType trustType, VerificationAttestationState state);

    /**
     * The subject's own attestations, newest first (the L32
     * deterministic order — matching V184's
     * {@code idx_verification_attestations_subject}), every state
     * included: the /me read answers the pair's own history, not just
     * the standing fact.
     */
    List<VerificationAttestation> findBySubjectUserIdOrderByCreatedAtDescIdDesc(UUID subjectUserId);
}
