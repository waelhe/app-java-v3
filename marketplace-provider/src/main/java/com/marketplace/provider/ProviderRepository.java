package com.marketplace.provider;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface ProviderRepository extends JpaRepository<ProviderProfile, UUID>, JpaSpecificationExecutor<ProviderProfile>, RevisionRepository<ProviderProfile, UUID, Integer> {

    /** Resolves the provider profile owned by a user ("me" seam, L20). */
    Optional<ProviderProfile> findByUserId(UUID userId);

    /**
     * L21: pessimistic row lock for the event-driven rating-average write —
     * concurrent async listeners serialize on the profile row instead of
     * racing the optimistic version (the loser's aggregate would be lost
     * until the event-publication resubmission retries it).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProviderProfile p where p.id = :id")
    Optional<ProviderProfile> findByIdForUpdate(UUID id);
}
