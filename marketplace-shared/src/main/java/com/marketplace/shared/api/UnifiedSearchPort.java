package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The unified search entry (§5.1 — «عقد البحث الموحد»): ONE entry by domain
 * and geography, dispatched to the measured per-domain sources. The contract
 * is framework-neutral (the {@link PagedRequest}/{@link PagedResponse} pair
 * — no Spring Data types cross the boundary).
 *
 * <p><b>No giant criteria object:</b> the unified entry carries only the
 * subset every connected source honestly shares — the text query and the
 * location node. Each domain's rich facets stay on their own measured
 * surface (the listings filters, the community categories); the unified
 * layer never invents parameters that are meaningless for a domain.
 *
 * <p><b>The ambiguous-location rule:</b> {@code locationId} passes through
 * exactly as supplied — an unknown node is rejected by the owning domain
 * path (the listings geo port 404s; the community path answers its own
 * membership contract). A missing location NEVER widens a local query into
 * a silent national one: the community path is always membership-scoped,
 * and the listings path's null location is its documented public-catalog
 * semantics.
 *
 * <p><b>Eligibility is enforced twice:</b> the domain path applies its own
 * visibility/membership/state filters, and the dispatcher adds none of its
 * own — no bypass path exists by construction (the dispatcher holds ports,
 * not repositories).
 *
 * <p><b>Composition-root dispatch:</b> the implementation lives in the
 * application assembly (the pom's own words: the app is the assembly
 * point) consuming the named SPI ports; when the source count grows past
 * the measured two, the dispatch migrates into the search module behind
 * the same contract — no signature change.
 */
public interface UnifiedSearchPort {

    /**
     * Execute the unified search for ONE domain.
     *
     * @param callerId   the authenticated caller's id (the community path
     *                   scopes to the caller's own membership; the listings
     *                   path ignores it — a null caller is the anonymous
     *                   form and only the community domain rejects it)
     * @param domain     the connected domain (the enum is the whitelist)
     * @param query      the text query (the domain path owns its
     *                   normalization/limits — the community path's
     *                   200-code-point/blank gates, the listings trigram
     *                   fallback)
     * @param locationId the caller's chosen geo node (passed through — see
     *                   the ambiguous-location rule)
     * @param request    the framework-neutral pagination request
     * @return the paged hits — source, time, state, and the original id
     */
    PagedResponse<UnifiedSearchHit> search(UUID callerId, UnifiedSearchDomain domain,
                                           String query, UUID locationId, PagedRequest request);
}
