package com.marketplace.discovery.spi;

import com.marketplace.shared.api.DiscoveryRowView;
import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * Phase 6 (the corrected GraphQL plan §11-Phase-6 — the first composite
 * user journey per the inventory #599 §7 stage 4): the discovery
 * module's read SPI for the GraphQL surface — the home rails read the
 * REST home endpoint already serves ({@code GET /api/v1/discovery} →
 * {@code DiscoveryService.homeRows}), on the {@code CatalogSpi} house
 * pattern: the app's DGS data fetcher delegates to THIS declared seam,
 * never to the service class or any repository — the eligibility,
 * ordering and geo-scope rules stay inside the owner (the inventory's
 * §8 governing rules: «الخادم يملك أهلية المحتوى وترتيبه والنطاق
 * الجغرافي»).
 *
 * <p>The interface lives in the owner module's {@code spi} package and
 * is exposed through Spring Modulith's {@code @NamedInterface} — the
 * same declared-seam model {@code CatalogSpi} established for the
 * catalog surface. The transport types it speaks
 * ({@link DiscoveryRowView} and its card) already live in shared-api —
 * the discovery projection's own cross-module contract since Wave D-1.</p>
 */
@NamedInterface("discovery-spi")
public interface DiscoverySpi {

    /**
     * The caller's home discovery rails — every rule the REST home
     * endpoint answers rides the same use case (the adapter duplicates
     * nothing): the caller's active membership is the scope (no
     * membership answers the honest EMPTY list, never a widened one —
     * AC-02-02), the §1.4 rail order is fixed
     * (URGENT_ALERTS → FOLLOWED_SOURCES → LOST_FOUND →
     * NEIGHBORHOOD_RECOMMENDATIONS → EVENTS_AND_OPPORTUNITIES →
     * FOR_YOU), each rail is bounded at the assembler's home card limit,
     * every empty rail is ABSENT from the response entirely (AC-20-07),
     * and every card carries its original source identity
     * ({@code sourceType + sourceId + updatedAt}) with an honest reason
     * and the paid/organic disclosure.
     *
     * @param callerId the authenticated caller's id (the membership
     *                 scope — resolved by the consuming surface's
     *                 principal seam, never a client parameter)
     * @return the caller's bounded home rails, in the fixed rail order;
     *         the honest empty list when the caller holds no active
     *         membership
     */
    List<DiscoveryRowView> homeRows(UUID callerId);
}
