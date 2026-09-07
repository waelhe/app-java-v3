package com.marketplace.provider;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface ProviderRepository extends JpaRepository<ProviderProfile, UUID>, JpaSpecificationExecutor<ProviderProfile>, RevisionRepository<ProviderProfile, UUID, Integer> {

    /** Resolves the provider profile owned by a user ("me" seam, L20). */
    Optional<ProviderProfile> findByUserId(UUID userId);
}
