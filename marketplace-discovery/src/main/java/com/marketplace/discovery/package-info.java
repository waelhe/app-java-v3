/**
 * Wave D-1 (plan #536 §1.4 / JT-20 — "صفوف اكتشاف موضوعية فوق سجل موحد"):
 * the home discovery rails module — the deterministic assembler of the
 * home surface's topic rails (URGENT_ALERTS, FOLLOWED_SOURCES, LOST_FOUND,
 * NEIGHBORHOOD_RECOMMENDATIONS, EVENTS_AND_OPPORTUNITIES, FOR_YOU).
 *
 * <p><b>The rail is a projection, never an owner</b>: this module holds NO
 * content copy of any kind — every card resolves back to its source record
 * through the shared-api ports ({@code CommunityDiscoveryPort},
 * {@code JobsDiscoveryPort}, {@code UrgentAlertsPort},
 * {@code FollowedSourcesPort}, {@code ProviderListingsPort}) and the owning
 * module re-validates state and visibility at open time (AC-20-10). The
 * only table it owns is the {@code discovery_impressions} ledger (V176) —
 * the born-complete display bookkeeping, written once and never mutated
 * (the V93 {@code provider_follow_alerts} discipline verbatim).</p>
 *
 * <p><b>No direct dependency on any sibling module</b>: all data crosses
 * the boundary exclusively through the shared named interfaces
 * ({@code shared :: shared-api} for the ports and views, {@code shared ::
 * shared-security} for the caller seam, {@code shared :: shared-jpa} for
 * the persistence machinery) — a sibling's repository is never touched
 * from here.</p>
 */
@org.springframework.modulith.NamedInterface("discovery")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-jpa", "shared :: shared-security"}
)
package com.marketplace.discovery;
