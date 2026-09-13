package com.marketplace.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;
import java.util.UUID;

/**
 * The realestate module's contribution to faceted search (realestate
 * systems plan L32 — implemented by the realestate module). The adopted
 * CodeRabbit architecture: property predicates live on
 * {@code property_details} (the realestate module's own table — a
 * cross-module subquery would break schema ownership under Modulith, so
 * the module itself answers with the matching LISTING-ID SET), and the
 * search composes that restriction onto the catalog query in the exact
 * shape of the existing restricted paths (SYSTEM.md §5 — the restricted
 * branch of {@code CatalogSearchPort}).
 *
 * <p>Contract (the house restricted-path discipline):
 * <ul>
 *   <li>the set forms return the listing ids matching ALL present
 *       criteria; an empty result is an honest empty page (the caller
 *       short-circuits, no catalog query);</li>
 *   <li>{@code providerIds} arrives only in the restricted forms and is
 *       always non-empty (the availability whitelist — the caller has
 *       handled the empty case);</li>
 *   <li>the paged forms additionally restrict to {@code activeListingIds}
 *       (the catalog's ACTIVE set — the search resolves it first) and page
 *       through the realestate-owned ordering ({@code area} sort) — the
 *       only sort direction the catalog cannot see.</li>
 * </ul>
 *
 * <p>Declared cost (plan debt D-E6): the set-restriction pattern's IN
 * clause grows with the qualified-results size — sufficient for a
 * Qudsayya-scale catalog by multiples; the closure threshold is a
 * measured 10K qualified listings per typical query.
 */
public interface RealestatePropertyFilterPort {

    /**
     * Listing ids whose property details match the criteria (unrestricted
     * form — no stay window).
     */
    Set<UUID> findListingIdsMatching(PropertyCriteria criteria);

    /**
     * Listing ids matching the criteria AND written by one of the given
     * providers (the window path: the availability whitelist).
     */
    Set<UUID> findListingIdsMatchingRestricted(PropertyCriteria criteria, Set<UUID> providerIds);

    /**
     * A DB-side paged slice of the matches, ordered per the pageable
     * (the {@code area} sort marker maps to the realestate-owned
     * {@code areaM2} column + id tiebreak). {@code activeListingIds} is
     * the catalog-resolved ACTIVE set — the realestate table does not know
     * listing status (and must not), so the caller supplies it.
     */
    Page<PropertyMatch> findMatchingPaged(PropertyCriteria criteria,
                                          Set<UUID> activeListingIds, Pageable pageable);

    /** The provider-restricted paged form (window + area sort). */
    Page<PropertyMatch> findMatchingPagedRestricted(PropertyCriteria criteria,
                                                    Set<UUID> activeListingIds,
                                                    Set<UUID> providerIds, Pageable pageable);

    /**
     * One matching listing with the sort-relevant field (the area). The
     * paged forms return these; the search assembles the catalog
     * summaries for the page's ids in order.
     *
     * @param listingId the matching catalog listing
     * @param areaM2    the property's area — null means undeclared (sorts
     *                  last in the area ordering)
     */
    record PropertyMatch(UUID listingId, Integer areaM2) {
    }
}
