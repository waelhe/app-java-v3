package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodMembership;
import com.marketplace.community.NeighborhoodMembershipRepository;
import com.marketplace.shared.api.NeighborhoodMembersPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * community module's implementation of the
 * {@link NeighborhoodMembersPort} cross-module read contract — the
 * official-urgent-alert fan-out resolves the scope's ACTIVE members
 * through this seam (the {@code CommunityMembershipAdapter} house
 * pattern verbatim: the interface lives in shared-api, the data owner
 * implements it, the consumer injects it — no cross-module repository
 * access).
 *
 * <p><b>The ACTIVE-members visibility, cloned verbatim:</b> the query is
 * the same {@code findByLocationId} read the neighborhood bridge's
 * member-alert loop runs ({@code NeighborhoodMembershipService} — the
 * L46 fan-out's own enumeration): Hibernate's {@code @SoftDelete}
 * filter hides left memberships from the derived query, so exactly the
 * ACTIVE rows are visible — the entity-level twin of V60's partial
 * unique index {@code ON (user_id) WHERE is_deleted = FALSE}. An empty
 * neighborhood answers an honest empty list — never a widened scope
 * (the AC-02-02 geographic discipline).
 */
@Component
@Transactional(readOnly = true)
public class NeighborhoodMembersAdapter implements NeighborhoodMembersPort {

    private final NeighborhoodMembershipRepository membershipRepository;

    public NeighborhoodMembersAdapter(NeighborhoodMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    @Override
    public List<UUID> getActiveMemberIds(UUID locationId) {
        return membershipRepository.findByLocationId(locationId).stream()
                .map(NeighborhoodMembership::getUserId)
                .toList();
    }
}
