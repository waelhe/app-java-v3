package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PropertyCriteria;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.data.domain.Sort;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    private final CatalogSearchPort port = mock(CatalogSearchPort.class);
    private final AvailabilityLookupPort availabilityPort = mock(AvailabilityLookupPort.class);
    private final GeoLookupPort geoPort = mock(GeoLookupPort.class);
    private final RealestatePropertyFilterPort filterPort = mock(RealestatePropertyFilterPort.class);
    private final SearchService service =
            new SearchService(port, availabilityPort, geoPort, filterPort);

    private static Page<ListingSummary> emptyPage() {
        return new PageImpl<>(List.of());
    }

    // ---- L27: the window path -------------------------------------------------

    private static final Instant CHECK_IN = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant CHECK_OUT = CHECK_IN.plusSeconds(3 * 24 * 3600);

    @Test
    void windowWithoutQuery_restrictsTheCriteriaSearchToAvailableProviders() {
        UUID available = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.searchByCriteriaRestricted(any(), any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT), PageRequest.of(0, 10));

        verify(availabilityPort).findAvailableProviderIds(CHECK_IN, CHECK_OUT);
        // The whitelist rides the restricted criteria query — the criteria
        // record passes through as-is (the catalog contract reads only its
        // category/price components, the window itself is already resolved
        // into the whitelist), and the unrestricted branches are never taken.
        verify(port).searchByCriteriaRestricted(argThat(SearchCriteria::hasWindow), eq(Set.of(available)), eq(PageRequest.of(0, 10)));
        verify(port, never()).listActive(any());
        verify(port, never()).searchByCriteria(any(), any());
    }

    @Test
    void windowWithQuery_restrictsTheFullTextSearchToAvailableProviders() {
        UUID available = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.searchFullTextRestricted(anyString(), any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria("yoga retreat", null, null, null, CHECK_IN, CHECK_OUT), PageRequest.of(0, 10));

        verify(port).searchFullTextRestricted(eq("yoga retreat"), eq(Set.of(available)), eq(PageRequest.of(0, 10)));
        // The unrestricted FTS branch is never taken when a window is present.
        verify(port, never()).searchFullText(anyString(), any());
    }

    @Test
    void windowWithNoAvailableProvider_isAnHonestEmptyPageWithoutAnyCatalogQuery() {
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of());

        Page<ListingSummary> page = service.search(new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT), PageRequest.of(0, 10));

        assertThat(page).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(port, never()).searchByCriteriaRestricted(any(), any(), any());
        verify(port, never()).searchFullTextRestricted(anyString(), any(), any());
        verify(port, never()).listActive(any());
    }

    // ---- The pre-L27 dispatch: unchanged (backward compatibility) --------------

    @Test
    void usesCriteriaSearchWhenPriceFilterProvided() {
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, BigDecimal.valueOf(10), null), PageRequest.of(0, 20));

        verify(port).searchByCriteria(any(), any());
        verify(port, never()).listActive(any());
    }

    // ---- I6: the guests dispatch ------------------------------------------------

    @Test
    void guestsOnlyCriterion_routesToTheCriteriaQuery_notListActive() {
        // The dispatch bug this change guards against: a guests-only
        // criterion (no query, no price, no category) must reach the
        // criteria query — listActive would silently bypass the capacity
        // filter.
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null, null, null, 4), PageRequest.of(0, 10));

        verify(port).searchByCriteria(argThat(c -> c.guests() != null && c.guests().equals(4)), eq(PageRequest.of(0, 10)));
        verify(port, never()).listActive(any());
        verify(port, never()).listByCategory(anyString(), any());
    }

    @Test
    void categoryWithGuests_routesToTheCriteriaQuery_notCategoryBranch() {
        // category+guests composes in ONE criteria query (the category
        // predicate rides the same native query) — the dedicated category
        // branch (which has no capacity predicate) is never taken.
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, "stay", null, null, null, null, 2), PageRequest.of(0, 10));

        verify(port).searchByCriteria(argThat(c -> "stay".equals(c.category()) && Integer.valueOf(2).equals(c.guests())), eq(PageRequest.of(0, 10)));
        verify(port, never()).listByCategory(anyString(), any());
    }

    @Test
    void guestsWithWindow_ridesTheRestrictedCriteriaQuery() {
        UUID available = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.searchByCriteriaRestricted(any(), any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT, 3), PageRequest.of(0, 10));

        verify(port).searchByCriteriaRestricted(
                argThat(c -> c.hasWindow() && Integer.valueOf(3).equals(c.guests())), eq(Set.of(available)), eq(PageRequest.of(0, 10)));
    }

    @Test
    void usesFullTextSearchWhenQueryProvided() {
        when(port.searchFullText(anyString(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria("hello world", null, null, null), PageRequest.of(0, 10));

        // Raw pass-through (trim only): websearch_to_tsquery owns the parsing.
        verify(port).searchFullText("hello world", PageRequest.of(0, 10));
    }

    @Test
    void passesRawInputThroughUnmangled() {
        when(port.searchFullText(anyString(), any())).thenReturn(emptyPage());

        // Quotes/parens/dashes are valid websearch_to_tsquery syntax, not
        // pre-mangled "&" tsquery operators (the old munging fed to_tsquery
        // invalid syntax -> SQL exception -> HTTP 500).
        service.search(new SearchCriteria("\"garden view\" -crab ((", null, null, null),
                PageRequest.of(0, 10));

        verify(port).searchFullText("\"garden view\" -crab ((", PageRequest.of(0, 10));
    }

    @Test
    void usesCategoryWhenNoQueryOrPrice() {
        when(port.listByCategory(anyString(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, "tech", null, null), PageRequest.of(0, 10));

        verify(port).listByCategory("tech", PageRequest.of(0, 10));
    }

    @Test
    void usesListActiveWhenNoCriteria() {
        when(port.listActive(any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, null, null), PageRequest.of(0, 10));

        verify(port).listActive(PageRequest.of(0, 10));
    }

    @Test
    void searchByCategory_delegates() {
        when(port.listByCategory(anyString(), any())).thenReturn(emptyPage());

        service.searchByCategory("books", PageRequest.of(0, 5));

        verify(port).listByCategory("books", PageRequest.of(0, 5));
    }

    @Test
    void searchAll_delegates() {
        when(port.listActive(any())).thenReturn(emptyPage());

        service.searchAll(PageRequest.of(0, 20));

        verify(port).listActive(PageRequest.of(0, 20));
    }

    // ---- L32: the property-facet flow -----------------------------------------

    @Test
    void legacyCriteria_neverTouchThePropertyPorts_byteIdenticalDispatch() {
        // a price criterion routes to the legacy criteria query (a category-only
        // criterion routes to listByCategory — both are the unmodified paths)
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, BigDecimal.TEN, null), PageRequest.of(0, 10));

        verify(geoPort, never()).findSelfAndDescendants(any());
        verify(filterPort, never()).findListingIdsMatching(any());
        verify(filterPort, never()).findListingIdsMatchingRestricted(any(), any());
        verify(port).searchByCriteria(any(), any());
    }

    @Test
    void propertyCriteria_withUnknownLocation_is404BeforeAnyCatalogQuery() {
        UUID unknown = UUID.randomUUID();
        when(geoPort.findSelfAndDescendants(unknown))
                .thenThrow(new com.marketplace.shared.api.ResourceNotFoundException("GeoLocation", unknown));

        assertThatThrownBy(() -> service.search(
                new SearchCriteria(null, null, null, null, null, null, null,
                        unknown, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 10)))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
        verify(port, never()).searchByCriteriaRestrictedToListings(any(), any(), any());
    }

    @Test
    void propertyCriteria_withEmptyMatchingSet_isAnHonestEmptyPageWithoutAnyCatalogQuery() {
        UUID location = UUID.randomUUID();
        when(geoPort.findSelfAndDescendants(location)).thenReturn(Set.of(location));
        when(filterPort.findListingIdsMatching(any())).thenReturn(Set.of());

        Page<ListingSummary> page = service.search(
                new SearchCriteria(null, null, null, null, null, null, null,
                        location, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 10));

        assertThat(page).isEmpty();
        assertThat(page.getTotalElements()).isZero();
        verify(port, never()).searchByCriteriaRestrictedToListings(any(), any(), any());
        verify(port, never()).searchFullTextRestrictedToListings(anyString(), any(), any());
    }

    @Test
    void propertyCriteria_withoutWindow_restrictsTheCriteriaSearchByTheMatchingSet() {
        UUID location = UUID.randomUUID();
        UUID matched = UUID.randomUUID();
        when(geoPort.findSelfAndDescendants(location)).thenReturn(Set.of(location));
        when(filterPort.findListingIdsMatching(any())).thenReturn(Set.of(matched));
        when(port.searchByCriteriaRestrictedToListings(any(), any(), any())).thenReturn(emptyPage());

        service.search(
                new SearchCriteria(null, null, null, null, null, null, null,
                        location, PropertyPurpose.RENT, null, null, null, null, null, null, null),
                PageRequest.of(0, 10));

        verify(filterPort).findListingIdsMatching(argThat((PropertyCriteria criteria) ->
                criteria.purpose() == PropertyPurpose.RENT
                        && criteria.locationIds() != null
                        && criteria.locationIds().contains(location)));
        verify(port).searchByCriteriaRestrictedToListings(any(), eq(Set.of(matched)), eq(PageRequest.of(0, 10)));
    }

    @Test
    void propertyCriteria_withWindow_composesBothRestrictions() {
        UUID location = UUID.randomUUID();
        UUID provider = UUID.randomUUID();
        UUID matched = UUID.randomUUID();
        when(geoPort.findSelfAndDescendants(location)).thenReturn(Set.of(location));
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(provider));
        when(filterPort.findListingIdsMatchingRestricted(any(), any())).thenReturn(Set.of(matched));
        when(port.searchByCriteriaRestrictedToListings(any(), any(), any())).thenReturn(emptyPage());

        service.search(
                new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT, null,
                        location, null, PropertyType.APARTMENT, 2, null, null, null, null, null),
                PageRequest.of(0, 10));

        // the provider whitelist rides the RESTRICTED filter form (the
        // realestate table's own provider column — no double IN on catalog)
        verify(filterPort).findListingIdsMatchingRestricted(
                argThat((PropertyCriteria criteria) -> criteria.propertyType() == PropertyType.APARTMENT
                        && criteria.minRooms() == 2),
                eq(Set.of(provider)));
        verify(port).searchByCriteriaRestrictedToListings(any(), eq(Set.of(matched)), any());
    }

    @Test
    void propertyCriteria_withTextQuery_usesTheRankedFtsBranch() {
        UUID matched = UUID.randomUUID();
        when(filterPort.findListingIdsMatching(any())).thenReturn(Set.of(matched));
        when(port.searchFullTextRestrictedToListings(anyString(), any(), any())).thenReturn(emptyPage());

        service.search(
                new SearchCriteria("شقة قدسيا", null, null, null, null, null, null,
                        null, PropertyPurpose.SALE, null, null, null, null, null, null, null),
                PageRequest.of(0, 10));

        verify(port).searchFullTextRestrictedToListings(eq("شقة قدسيا"), eq(Set.of(matched)), any());
        verify(port, never()).searchByCriteriaRestrictedToListings(any(), any(), any());
    }

    @Test
    void areaSort_composesThePagedPropertyFlow() {
        UUID activeOne = UUID.randomUUID();
        UUID activeTwo = UUID.randomUUID();
        var matchOne = new RealestatePropertyFilterPort.PropertyMatch(activeOne, 80);
        var matchTwo = new RealestatePropertyFilterPort.PropertyMatch(activeTwo, 120);
        when(port.findActiveListingIds()).thenReturn(Set.of(activeOne, activeTwo));
        when(filterPort.findMatchingPaged(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(matchTwo, matchOne), PageRequest.of(0, 2), 2));
        when(port.findSummariesByIds(anyList())).thenReturn(List.of(
                summaryOf(activeTwo), summaryOf(activeOne)));

        Page<ListingSummary> page = service.search(
                new SearchCriteria(null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 2, Sort.by(Sort.Direction.ASC, "area")));

        // the property side owns the ordering + the total; the summaries
        // follow the property order
        verify(filterPort).findMatchingPaged(any(), eq(Set.of(activeOne, activeTwo)), any());
        verify(port).findSummariesByIds(List.of(activeTwo, activeOne));
        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(activeTwo, activeOne);
        assertThat(page.getTotalElements()).isEqualTo(2L);
    }

    @Test
    void areaSort_withTextQuery_ranksByRelevanceInstead() {
        when(port.searchFullText(anyString(), any())).thenReturn(emptyPage());

        // CodeRabbit PR #299 round 1: the area marker selects the property
        // flow ONLY for blank queries — a text query rides the LEGACY text
        // path unrestrained (listings without property details stay
        // eligible; the documented "text searches ignore the sort").
        service.search(
                new SearchCriteria("loft", null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "area")));

        verify(port).searchFullText(eq("loft"), any());
        verify(port, never()).searchFullTextRestrictedToListings(anyString(), any(), any());
        verify(port, never()).findActiveListingIds();
        verify(filterPort, never()).findListingIdsMatching(any());
        verify(filterPort, never()).findMatchingPaged(any(), any(), any());
    }

    @Test
    void priceSort_onPlainFilterSearch_ridesTheFacetedPath_withIdTiebreak() {
        when(port.searchByCriteriaFaceted(any(), any())).thenReturn(emptyPage());

        // CodeRabbit PR #299 round 1 (normalize ONCE): the service consumes
        // the controller-normalized representation — the mapped name
        // (priceCents) is what arrives.
        service.search(
                new SearchCriteria(null, "realestate", null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "priceCents")
                        .and(Sort.by(Sort.Direction.ASC, "id"))));

        // price -> priceCents + id ASC tiebreak rides the FACET call (no
        // property criteria — no realestate round trip at all)
        verify(filterPort, never()).findListingIdsMatching(any());
        verify(port).searchByCriteriaFaceted(any(),
                argThat((org.springframework.data.domain.Pageable pageable) ->
                        pageable.getSort().toString().equals("priceCents: DESC,id: ASC")
                                || pageable.getSort().toString().equals("priceCents: DESC,id:ASC")));
    }

    @Test
    void priceSort_withPropertyCriteria_ridesTheRestrictedFacetedPath() {
        UUID matched = UUID.randomUUID();
        when(filterPort.findListingIdsMatching(any())).thenReturn(Set.of(matched));
        when(port.searchByCriteriaRestrictedToListings(any(), any(), any())).thenReturn(emptyPage());

        service.search(
                new SearchCriteria(null, null, null, null, null, null, null,
                        null, PropertyPurpose.RENT, null, null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "priceCents")
                        .and(Sort.by(Sort.Direction.ASC, "id"))));

        verify(port).searchByCriteriaRestrictedToListings(any(), eq(Set.of(matched)),
                argThat((org.springframework.data.domain.Pageable pageable) ->
                        pageable.getSort().toString().equals("priceCents: DESC,id: ASC")
                                || pageable.getSort().toString().equals("priceCents: DESC,id:ASC")));
    }

    @Test
    void priceSort_onWindowedSearch_keepsTheLegacyDeterministicOrder() {
        UUID available = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.searchByCriteriaRestricted(any(), any(), any())).thenReturn(emptyPage());

        service.search(
                new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "price")));

        // documented scope boundary: windowed searches keep the L27
        // restricted path (id order); the sort is ignored
        verify(port).searchByCriteriaRestricted(any(), any(), any());
        verify(port, never()).searchByCriteriaFaceted(any(), any());
    }

    private static ListingSummary summaryOf(UUID id) {
        return new ListingSummary(id, "listing " + id, "realestate",
                BigDecimal.valueOf(1000, 2), "SAR", "Provider");
    }

    // ---- P1 (postgis plan): the radius flow --------------------------------

    private static final BigDecimal LAT = new BigDecimal("33.558889");
    private static final BigDecimal LNG = new BigDecimal("36.056944");
    private static final long RADIUS_METERS = 10_000L;

    private static SearchCriteria radiusCriteria() {
        return new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10"));
    }

    @Test
    void radiusWithoutSort_runsTheSetFlow_onTheUnrestrictedRadiusQuery() {
        UUID near = UUID.randomUUID();
        when(filterPort.findListingIdsWithinRadius(LAT, LNG, RADIUS_METERS)).thenReturn(Set.of(near));
        when(port.searchByCriteriaRestrictedToListings(any(), any(), any())).thenReturn(emptyPage());

        service.search(radiusCriteria(), PageRequest.of(0, 10));

        verify(filterPort).findListingIdsWithinRadius(LAT, LNG, RADIUS_METERS);
        verify(filterPort, never()).findListingIdsWithinRadiusRestricted(any(), any(), anyLong(), any());
        verify(port).searchByCriteriaRestrictedToListings(any(), eq(Set.of(near)), any());
        verify(port, never()).searchByCriteria(any(), any());
    }

    @Test
    void emptyRadiusSet_isAnHonestEmptyPage_noCatalogQuery() {
        when(filterPort.findListingIdsWithinRadius(any(), any(), anyLong())).thenReturn(Set.of());

        Page<ListingSummary> page = service.search(radiusCriteria(), PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
        verify(port, never()).searchByCriteriaRestrictedToListings(any(), any(), any());
        verify(port, never()).searchFullTextRestrictedToListings(anyString(), any(), any());
    }

    @Test
    void radiusWithWindow_usesTheProviderRestrictedForm() {
        UUID available = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(filterPort.findListingIdsWithinRadiusRestricted(LAT, LNG, RADIUS_METERS, Set.of(available)))
                .thenReturn(Set.of(near));
        when(port.searchByCriteriaRestrictedToListings(any(), any(), any())).thenReturn(emptyPage());

        SearchCriteria windowed = new SearchCriteria(null, null, null, null,
                CHECK_IN, CHECK_OUT, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("10"));
        service.search(windowed, PageRequest.of(0, 10));

        verify(filterPort).findListingIdsWithinRadiusRestricted(LAT, LNG, RADIUS_METERS, Set.of(available));
        verify(filterPort, never()).findListingIdsWithinRadius(any(), any(), anyLong());
    }

    @Test
    void emptyAvailabilityWhitelist_shortCircuitsBeforeTheRadiusQuery() {
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of());

        SearchCriteria windowed = new SearchCriteria(null, null, null, null,
                CHECK_IN, CHECK_OUT, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("10"));
        Page<ListingSummary> page = service.search(windowed, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
        verify(filterPort, never()).findListingIdsWithinRadiusRestricted(any(), any(), anyLong(), any());
    }

    @Test
    void radiusAndFacets_intersectBeforeTheCatalogQuery() {
        // D-P10: the radius ANDs with the geo hierarchy and the facets —
        // the set-restriction composition.
        UUID nearWithFacet = UUID.randomUUID();
        when(filterPort.findListingIdsWithinRadius(LAT, LNG, RADIUS_METERS))
                .thenReturn(Set.of(nearWithFacet, UUID.randomUUID()));
        when(geoPort.findSelfAndDescendants(any())).thenReturn(Set.of(UUID.randomUUID()));
        when(filterPort.findListingIdsMatching(any())).thenReturn(Set.of(nearWithFacet));
        when(port.searchByCriteriaRestrictedToListings(any(), any(), any())).thenReturn(emptyPage());

        SearchCriteria withFacet = new SearchCriteria(null, null, null, null, null, null, null,
                UUID.randomUUID(), PropertyPurpose.RENT, null, null, null, null,
                LAT, LNG, new BigDecimal("10"));
        service.search(withFacet, PageRequest.of(0, 10));

        verify(port).searchByCriteriaRestrictedToListings(any(), eq(Set.of(nearWithFacet)), any());
    }

    @Test
    void facetIntersectionToEmpty_isAnHonestEmptyPage() {
        when(filterPort.findListingIdsWithinRadius(any(), any(), anyLong()))
                .thenReturn(Set.of(UUID.randomUUID()));
        when(geoPort.findSelfAndDescendants(any())).thenReturn(Set.of(UUID.randomUUID()));
        when(filterPort.findListingIdsMatching(any())).thenReturn(Set.of());

        SearchCriteria withFacet = new SearchCriteria(null, null, null, null, null, null, null,
                UUID.randomUUID(), PropertyPurpose.RENT, null, null, null, null,
                LAT, LNG, new BigDecimal("10"));
        Page<ListingSummary> page = service.search(withFacet, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isZero();
        verify(port, never()).searchByCriteriaRestrictedToListings(any(), any(), any());
    }

    @Test
    void radiusWithTextQuery_ranksByRelevance_theDistanceSortIsIgnored() {
        UUID near = UUID.randomUUID();
        when(filterPort.findListingIdsWithinRadius(LAT, LNG, RADIUS_METERS)).thenReturn(Set.of(near));
        when(port.searchFullTextRestrictedToListings(anyString(), any(), any())).thenReturn(emptyPage());

        SearchCriteria text = new SearchCriteria("شقة", null, null, null, null, null, null,
                null, null, null, null, null, null, LAT, LNG, new BigDecimal("10"));
        service.search(text, PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance")));

        // documented scope boundary: text queries rank by relevance — the
        // set flow runs (never the distance-paged flow)
        verify(port).searchFullTextRestrictedToListings(eq("شقة"), eq(Set.of(near)), any());
        verify(filterPort, never()).findWithinRadiusPaged(any(), any(), anyLong(), any(), any());
    }

    @Test
    void distanceSort_pagesThroughTheRadiusOrdering_withTheFacetComposition() {
        UUID active = UUID.randomUUID();
        UUID facetMatch = active; // the facet set and the ACTIVE set agree
        UUID nearest = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(geoPort.findSelfAndDescendants(any())).thenReturn(Set.of(UUID.randomUUID()));
        when(filterPort.findListingIdsMatching(any())).thenReturn(Set.of(facetMatch));
        when(port.findActiveListingIds()).thenReturn(Set.of(active));
        when(filterPort.findWithinRadiusPaged(eq(LAT), eq(LNG), eq(RADIUS_METERS),
                eq(Set.of(active)), any()))
                .thenReturn(new PageImpl<>(List.of(nearest, next),
                        PageRequest.of(0, 10), 2));
        when(port.findSummariesByIds(List.of(nearest, next)))
                .thenReturn(List.of(summaryOf(nearest), summaryOf(next)));

        SearchCriteria withFacet = new SearchCriteria(null, null, null, null, null, null, null,
                UUID.randomUUID(), PropertyPurpose.RENT, null, null, null, null,
                LAT, LNG, new BigDecimal("10"));
        Page<ListingSummary> page = service.search(withFacet,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance")));

        assertThat(page.getContent()).extracting(ListingSummary::id)
                .containsExactly(nearest, next);
        assertThat(page.getTotalElements()).isEqualTo(2);
        // the PORT receives the distance marker (the ADAPTER consumes it —
        // the unsorted delivery to the native query is the adapter's own
        // guarded contract, see PropertyFilterAdapterTest)
        verify(filterPort).findWithinRadiusPaged(eq(LAT), eq(LNG), eq(RADIUS_METERS), eq(Set.of(active)),
                argThat((org.springframework.data.domain.Pageable pageable) ->
                        pageable.getSort().toString().equals("distance: ASC")));
    }

    @Test
    void distanceSort_withoutRadiusCriteria_is400BeforeAnyQuery() {
        assertThatThrownBy(() -> service.search(
                new SearchCriteria(null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance"))))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class)
                .hasMessageContaining("requires the radius criteria");
        verify(port, never()).findActiveListingIds();
        verify(filterPort, never()).findWithinRadiusPaged(any(), any(), anyLong(), any(), any());
    }

    @Test
    void radiusWithoutDistanceSort_neverTouchesThePagedForm() {
        when(filterPort.findListingIdsWithinRadius(any(), any(), anyLong())).thenReturn(Set.of());
        when(port.searchByCriteriaRestrictedToListings(any(), any(), any())).thenReturn(emptyPage());

        service.search(radiusCriteria(), PageRequest.of(0, 10));

        verify(filterPort, never()).findWithinRadiusPaged(any(), any(), anyLong(), any(), any());
        verify(filterPort, never()).findWithinRadiusPagedRestricted(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void windowedDistanceSort_usesTheRestrictedPagedForm() {
        UUID available = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        UUID nearest = UUID.randomUUID();
        when(availabilityPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT)).thenReturn(Set.of(available));
        when(port.findActiveListingIds()).thenReturn(Set.of(active));
        when(filterPort.findWithinRadiusPagedRestricted(eq(LAT), eq(LNG), eq(RADIUS_METERS),
                eq(Set.of(active)), eq(Set.of(available)), any()))
                .thenReturn(new PageImpl<>(List.of(nearest), PageRequest.of(0, 10), 1));
        when(port.findSummariesByIds(List.of(nearest))).thenReturn(List.of(summaryOf(nearest)));

        SearchCriteria windowed = new SearchCriteria(null, null, null, null,
                CHECK_IN, CHECK_OUT, null, null, null, null, null, null, null,
                LAT, LNG, new BigDecimal("10"));
        Page<ListingSummary> page = service.search(windowed,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "distance")));

        assertThat(page.getContent()).extracting(ListingSummary::id).containsExactly(nearest);
        verify(filterPort).findWithinRadiusPagedRestricted(eq(LAT), eq(LNG), eq(RADIUS_METERS),
                eq(Set.of(active)), eq(Set.of(available)), any());
        verify(filterPort, never()).findWithinRadiusPaged(any(), any(), anyLong(), any(), any());
    }
}
