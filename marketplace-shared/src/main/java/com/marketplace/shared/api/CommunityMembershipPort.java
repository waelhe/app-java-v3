package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a user's ACTIVE community membership neighborhood — the scope
 * the whole neighborhood surface speaks (the L42 discipline: "the
 * membership IS the scope"). The discovery rails read it to scope every
 * geo-scoped rail to the caller's own level-3 neighborhood — never a
 * silent national widening (AC-02-02 / §1.4: geographic expansion is an
 * explicit user act, not a default).
 *
 * <p>The house pattern verbatim (the {@code ListingPriceProvider}
 * precedent): the interface lives in shared-api, the data owner
 * (marketplace-community, the V60/V91 membership machinery) implements
 * it, the consumer injects it — no module boundary is crossed in code.</p>
 */
public interface CommunityMembershipPort {

    /**
     * @return the caller's active membership's level-3 neighborhood id in
     *         the geo_locations space, or empty when the user holds no
     *         active membership (the discovery answer is then an honest
     *         empty list, never a widened scope)
     */
    Optional<UUID> getActiveNeighborhoodId(UUID userId);
}
