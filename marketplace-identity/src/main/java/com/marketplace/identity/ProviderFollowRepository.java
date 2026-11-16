package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderFollowRepository extends JpaRepository<ProviderFollow, UUID> {

    /**
     * W4 (G21): the duplicate gate's fact — a LIVE follow of this pair (the
     * {@code @SoftDelete} filter keeps withdrawn rows out, so a re-follow
     * after an unfollow is legal by construction).
     */
    boolean existsByUserIdAndProviderUserId(UUID userId, UUID providerUserId);

    /** The member's "my follows" page — newest first, the L32 deterministic order. */
    org.springframework.data.domain.Page<ProviderFollow> findByUserIdOrderByCreatedAtDescIdDesc(
            UUID userId, org.springframework.data.domain.Pageable pageable);

    /**
     * W4 (G21): the activation bridge's scan — every live follow of one
     * provider, the indexed read the {@code ListingActivatedEvent.providerId}
     * (A1: users.id space) joins on directly. Linear fan-out by design (the
     * community bridge's {@code findByLocationId} twin — the D-C1-style
     * declared bound: follower counts past the batching threshold move to
     * batched publication, a documented evolution point, not a silent one).
     */
    List<ProviderFollow> findByProviderUserId(UUID providerUserId);

    /** The unfollow's honest 404 fact — the caller's own (user, id) pair. */
    Optional<ProviderFollow> findByIdAndUserId(UUID id, UUID userId);
}
