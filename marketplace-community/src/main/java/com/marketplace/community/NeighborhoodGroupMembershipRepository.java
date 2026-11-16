package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The group memberships' own repository (L51). The EventRsvpRepository
 * shape verbatim — three read forms serve the whole layer, all through
 * Hibernate's {@code @SoftDelete} filter (left memberships are absent
 * from every derived query and JPQL predicate without any predicate of
 * our own):
 *
 * <ul>
 *   <li>the one-membership lookup {@link #findByGroupIdAndMemberId(UUID, UUID)}
 *       — the service's explicit 409 check and the leave's own row (the
 *       honest 404 when there is no live membership to remove);</li>
 *   <li>the member's own live memberships across a page of groups (the
 *       board's joinedByMe);</li>
 *   <li>the board's grouped count {@link #countByGroupIdIn(Collection)}
 *       — one aggregate over the page's group ids (the members count
 *       the board read carries), served by the V91 unique index's
 *       group_id-leading prefix scan.</li>
 * </ul>
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every membership taken and left is a revision the
 * export surface reads.
 */
public interface NeighborhoodGroupMembershipRepository
        extends JpaRepository<NeighborhoodGroupMembership, UUID>,
                RevisionRepository<NeighborhoodGroupMembership, UUID, Integer> {

    /** The member's own LIVE membership on one group — the one-membership lookup. */
    Optional<NeighborhoodGroupMembership> findByGroupIdAndMemberId(UUID groupId, UUID memberId);

    /** The caller's own LIVE memberships across a page of groups (the board's joinedByMe). */
    List<NeighborhoodGroupMembership> findByMemberIdAndGroupIdIn(UUID memberId, Collection<UUID> groupIds);

    /**
     * The board's count projection: live memberships grouped per group
     * over the page's ids. Hibernate's soft-delete filter appends the
     * is_deleted guard to the JPQL itself, so the projection counts
     * exactly what the reads see.
     */
    @Query("select m.groupId as groupId, count(m) as totalCount "
            + "from NeighborhoodGroupMembership m where m.groupId in :groupIds group by m.groupId")
    List<GroupMembershipCount> countByGroupIdIn(Collection<UUID> groupIds);

    /** The grouped count's own projection (Spring Data's interface projection). */
    interface GroupMembershipCount {
        UUID getGroupId();

        long getTotalCount();
    }
}
