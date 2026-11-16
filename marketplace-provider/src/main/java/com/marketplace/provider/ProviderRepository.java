package com.marketplace.provider;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderRepository extends JpaRepository<ProviderProfile, UUID>, JpaSpecificationExecutor<ProviderProfile>, RevisionRepository<ProviderProfile, UUID, Integer> {

    /** Resolves the provider profile owned by a user ("me" seam, L20). */
    Optional<ProviderProfile> findByUserId(UUID userId);

    /**
     * W4 (yelp-level plan §5 — G21): the batch form — one query for a
     * whole "my follows" page's followed providers (the
     * {@code UserLookupPort.findAllByIds} W1 precedent: "batch resolution
     * via findAllById to avoid N+1 queries"). User ids with no provider
     * row are simply absent (the caller's own fallback applies).
     */
    List<ProviderProfile> findByUserIdIn(Collection<UUID> userIds);

    /**
     * L21 + W1 (§4.4): pessimistic row lock for the event-driven
     * rating-pair write — concurrent async listeners serialize on the
     * profile row instead of racing the optimistic version (the loser's
     * aggregate would be lost until the event-publication resubmission
     * retries it). <b>Resolved by USER id (W1's measured correction):</b>
     * the review events carry a review id; the review's {@code provider_id}
     * physically references {@code users(id)} (V6 — the A1 convention), so
     * the lock must be taken on the profile OWNED BY that user. The old
     * {@code findByIdForUpdate} lookup compared the users.id against
     * {@code provider_profiles.id} and silently skipped on every
     * production-shaped pair of id spaces — the double mismatch the plan
     * §4.4 names.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProviderProfile p where p.userId = :userId")
    Optional<ProviderProfile> findByUserIdForUpdate(UUID userId);
}
