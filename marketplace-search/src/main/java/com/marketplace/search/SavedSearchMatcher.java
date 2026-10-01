package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PropertyCriteria;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): does ONE
 * newly activated listing match ONE stored {@link SearchCriteria}?
 *
 * <p><b>The faithfulness rule (the plan: "استعلام فلترة بالمواصفات نفسها"):</b>
 * every branch composes the SAME port calls the interactive search
 * dispatch makes, restricted to the single listing — the restricted-to-
 * listings catalog forms for the catalog-side predicates (category /
 * price / guests / text), the realestate filter port's id sets for the
 * property facets, the geo port's descendant set for the location
 * hierarchy, and the availability port's provider whitelist for the stay
 * window. A saved search's alert means exactly "this listing would have
 * appeared in that search's results" — no second, driftier matching
 * semantics is allowed to exist.
 *
 * <p><b>The dispatch mirror (measured against {@code SearchService.search}):
 * the radius branch composes the radius id set AND the facet set AND the
 * catalog-side predicates (D-P10); the property branch composes the facet
 * set AND the catalog side; the window restricts the provider via the
 * availability whitelist (the listing's provider arrives on the event);
 * text queries always ride the full-text form. Sorts are irrelevant for
 * a membership test — only membership is asked.
 */
@Component
public class SavedSearchMatcher {

    /** The existence probe: page 0, size 1, unsorted — membership is all that is asked. */
    private static final PagedRequest EXISTENCE_PROBE = PagedRequest.of(0, 1);

    private final CatalogSearchPort catalogSearchPort;
    private final GeoLookupPort geoLookupPort;
    private final RealestatePropertyFilterPort realestatePropertyFilterPort;
    private final AvailabilityLookupPort availabilityLookupPort;

    public SavedSearchMatcher(CatalogSearchPort catalogSearchPort,
                              GeoLookupPort geoLookupPort,
                              RealestatePropertyFilterPort realestatePropertyFilterPort,
                              AvailabilityLookupPort availabilityLookupPort) {
        this.catalogSearchPort = catalogSearchPort;
        this.geoLookupPort = geoLookupPort;
        this.realestatePropertyFilterPort = realestatePropertyFilterPort;
        this.availabilityLookupPort = availabilityLookupPort;
    }

    /**
     * @param criteria  the stored search criteria (already validated at save
     *                  time by the record's own gates)
     * @param listingId the newly activated listing
     * @param providerId the listing's provider (the users.id-space fact on
     *                   {@code ListingActivatedEvent}) — the stay-window
     *                   branch's whitelist membership key
     */
    public boolean matches(SearchCriteria criteria, UUID listingId, UUID providerId) {
        Set<UUID> single = Set.of(listingId);

        // The stay window gates FIRST — exactly like the dispatch: a window
        // nobody can serve is an honest no-match (the empty-whitelist
        // short-circuit), and the provider must hold an available slot.
        if (criteria.hasWindow()
                && !availabilityLookupPort.findAvailableProviderIds(
                        criteria.checkIn(), criteria.checkOut()).contains(providerId)) {
            return false;
        }

        // The realestate-owned membership sets — D-P10: the radius ANDs
        // with the property facets, exactly like dispatchRadius composes
        // them (CodeRabbit round 1: the former else-if let a radius
        // criteria silently bypass the facet predicates — a rooms>=5 saved
        // search could alert on a 2-room flat inside the circle).
        Set<UUID> propertySet = null;
        if (criteria.hasRadius()) {
            propertySet = realestatePropertyFilterPort.findListingIdsWithinRadius(
                    criteria.latitude(), criteria.longitude(), criteria.radiusMeters());
        }
        if (criteria.hasPropertyCriteria()) {
            Set<UUID> facetSet = realestatePropertyFilterPort.findListingIdsMatching(
                    toPropertyCriteria(criteria));
            propertySet = propertySet == null ? facetSet
                    : propertySet.stream().filter(facetSet::contains).collect(java.util.stream.Collectors.toSet());
        }
        if (propertySet != null && !propertySet.contains(listingId)) {
            return false;
        }
        // The catalog-side predicates (text / category / price / guests),
        // restricted to the single listing through the same restricted
        // forms the dispatch composes. R6 (comprehensive-review-ar fix
        // plan §4, Wave 5): the text branch passes the FULL criteria —
        // a saved search's text query composes with its filters exactly
        // like the interactive dispatch now does (the faithfulness rule:
        // no second, driftier matching semantics may exist). Before the
        // composition, a text-bearing saved search alerted on the
        // UNFILTERED text match set — the matcher's own copy of the R6
        // defect.
        String query = criteria.query();
        boolean textQuery = query != null && !query.isBlank();
        PagedResponse<ListingSummary> page = textQuery
                ? catalogSearchPort.searchFullTextRestrictedToListings(criteria, single, EXISTENCE_PROBE)
                : catalogSearchPort.searchByCriteriaRestrictedToListings(criteria, single, EXISTENCE_PROBE);
        return !page.isEmpty();
    }

    /** The dispatch's own mapping (SearchService.toPropertyCriteria, verbatim). */
    private PropertyCriteria toPropertyCriteria(SearchCriteria criteria) {
        Set<UUID> locationIds = criteria.locationId() != null
                ? geoLookupPort.findSelfAndDescendants(criteria.locationId())
                : null;
        return new PropertyCriteria(
                criteria.purpose(),
                criteria.propertyType(),
                criteria.minRooms(),
                criteria.minBathrooms(),
                criteria.minAreaM2(),
                locationIds);
    }
}
