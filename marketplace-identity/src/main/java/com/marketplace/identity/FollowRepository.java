package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The generalized follow's own repository (JT-20). Every derived query
 * rides Hibernate's {@code @SoftDelete} filter (the
 * {@code ProviderFollowRepository} rationale verbatim): a withdrawn
 * follow is absent from the reads, so a re-follow after an unfollow is
 * legal by construction and the idempotent replay only ever sees LIVE
 * rows.
 */
public interface FollowRepository extends JpaRepository<Follow, UUID> {

    /**
     * The idempotent write path's fact — the LIVE follow of this
     * (member, type, source) triple; a replay answers the standing row
     * instead of a 409 (the generalized surface's own idempotent
     * contract), and the withdraw finds the pair to free.
     */
    Optional<Follow> findByUserIdAndFollowableTypeAndFollowableId(
            UUID userId, FollowableType followableType, UUID followableId);

    /** The member's own list — newest first, the L32 deterministic order. */
    List<Follow> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId);

    /** The member's own list of ONE type — the same deterministic order. */
    List<Follow> findByUserIdAndFollowableTypeOrderByCreatedAtDescIdDesc(
            UUID userId, FollowableType followableType);
}
