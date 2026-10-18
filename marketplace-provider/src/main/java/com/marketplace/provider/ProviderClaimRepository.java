package com.marketplace.provider;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.UUID;

/**
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): the claim
 * cycle's own repository — {@link JpaRepository} queries plus the Envers
 * {@link RevisionRepository} seam this module's every aggregate carries
 * (the {@link ProviderRepository} shape).
 */
public interface ProviderClaimRepository
        extends JpaRepository<ProviderClaim, UUID>, RevisionRepository<ProviderClaim, UUID, Integer> {

    /**
     * The one-live-claim pre-write gate: does a PENDING claim already
     * exist on this page? Answered BEFORE any write (the house gate
     * order); the V233 partial unique index {@code uq_provider_claims_
     * one_live} is the concurrent-race backstop under it (the L31/23505
     * law — the service translates the race to the same friendly 409).
     */
    boolean existsByProviderIdAndState(UUID providerId, ProviderClaimState state);

    /**
     * The administrative queue's read (the {@code UrgentAlertSource}
     * verification-queue shape): the optional provider and state axes —
     * both present, one, or neither (the whole registry).
     */
    Page<ProviderClaim> findByProviderIdAndState(UUID providerId, ProviderClaimState state,
                                                 Pageable pageable);

    /** The queue's provider axis alone. */
    Page<ProviderClaim> findByProviderId(UUID providerId, Pageable pageable);

    /** The queue's state axis alone (the reviewable PENDING board). */
    Page<ProviderClaim> findByState(ProviderClaimState state, Pageable pageable);
}
