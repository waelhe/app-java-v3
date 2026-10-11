package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Resolves whether a neighborhood group exists — the generalized follow's
 * GROUP-leg existence gate (the discovery wave's {@code follows} table,
 * AC-20-05).
 *
 * <p>House pattern: interface in shared-api, marketplace-community
 * implements it ({@code GroupLookupAdapter} over the groups' own
 * repository), the consumer (marketplace-identity's follow write path)
 * injects it — the {@code PostLookupPort} / {@code ListingPriceProvider}
 * seam verbatim: no module dependency, a plain UUID in, a live-only
 * boolean out (Hibernate's {@code @SoftDelete} keeps the withdrawn groups
 * out, so a retired club cannot gain new followers).
 */
public interface GroupLookupPort {

    /**
     * @return true when the group exists and is live — a withdrawn group
     *         answers false (the same honesty the groups board read applies).
     */
    boolean exists(UUID groupId);
}
