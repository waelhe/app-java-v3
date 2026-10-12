package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodMembership;
import com.marketplace.community.NeighborhoodMembershipRepository;
import com.marketplace.shared.api.CommunityMembershipPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * JT-20 (#536 discovery waves D1-D4): the community module's
 * implementation of the {@link CommunityMembershipPort} cross-module
 * read contract — the discovery rails resolve the caller's scope through
 * this seam, never through a module dependency (the {@code PostLookupAdapter}
 * house pattern: the interface lives in shared-api, the data owner
 * implements it, the consumer injects it — no cross-module repository
 * access).
 *
 * <p><b>The active-membership gate, cloned verbatim:</b> the query is
 * the same {@code findByUserId} read the neighborhood services' 403 gate
 * runs ({@code NeighborhoodPostService.requireMembership} and its event
 * sibling): Hibernate's {@code @SoftDelete} filter hides left
 * memberships from the derived query, so exactly the ACTIVE row is
 * visible — the entity-level twin of V60's partial unique index
 * {@code ON (user_id) WHERE is_deleted = FALSE} (G-N1's default). An
 * absent membership answers {@code Optional.empty()} — the port's own
 * honest-empty contract: the discovery answer is an empty list, never a
 * widened scope (AC-02-02: geographic expansion is an explicit user
 * act, not a default).
 */
@Component
@Transactional(readOnly = true)
public class CommunityMembershipAdapter implements CommunityMembershipPort {

    private final NeighborhoodMembershipRepository membershipRepository;

    public CommunityMembershipAdapter(NeighborhoodMembershipRepository membershipRepository) {
        this.membershipRepository = membershipRepository;
    }

    @Override
    public Optional<UUID> getActiveNeighborhoodId(UUID userId) {
        return membershipRepository.findByUserId(userId)
                .map(NeighborhoodMembership::getLocationId);
    }
}
