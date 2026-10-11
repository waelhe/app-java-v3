package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The verification credential store (ADR-0001, plan §Phase 1). Derived
 * queries only — the official Spring Data JPA query derivation.
 */
public interface VerificationCredentialRepository
        extends JpaRepository<VerificationCredential, UUID> {

    /** The account's own history — newest first (the /me surface). */
    List<VerificationCredential> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** The admin review queue — filtered by status. */
    org.springframework.data.domain.Page<VerificationCredential> findByStatus(
            VerificationCredentialStatus status, org.springframework.data.domain.Pageable pageable);

    /**
     * The one-active-application-per-type gate: a PENDING or APPROVED
     * credential of the same type blocks a new submission (the resubmission
     * path opens after REJECTED/REVOKED — the terminal states).
     */
    boolean existsByUserIdAndCredentialTypeAndStatusIn(
            UUID userId, VerificationCredentialType credentialType,
            Collection<VerificationCredentialStatus> statuses);
}
