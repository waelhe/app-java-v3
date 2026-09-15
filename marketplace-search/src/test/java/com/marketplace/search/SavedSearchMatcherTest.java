package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PropertyCriteria;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): the
 * matcher's branch composition in isolation — every branch must compose
 * the SAME port calls the interactive search dispatch makes (the
 * faithfulness rule), so a saved search's alert means exactly "this
 * listing would have appeared in that search's results".
 */
@ExtendWith(MockitoExtension.class)
class SavedSearchMatcherTest {

    private static final UUID LISTING = UUID.randomUUID();
    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    @Mock
    private CatalogSearchPort catalogSearchPort;

    @Mock
    private GeoLookupPort geoLookupPort;

    @Mock
    private RealestatePropertyFilterPort realestatePropertyFilterPort;

    @Mock
    private AvailabilityLookupPort availabilityLookupPort;

    @InjectMocks
    private SavedSearchMatcher matcher;

    private void catalogAnswers(List<ListingSummary> content) {
        lenient().when(catalogSearchPort.searchByCriteriaRestrictedToListings(
                        any(SearchCriteria.class), anySet(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content));
        lenient().when(catalogSearchPort.searchFullTextRestrictedToListings(
                        anyString(), anySet(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content));
    }

    private static SearchCriteria legacy() {
        return new SearchCriteria(null, "stay", null, null);
    }

    private static SearchCriteria property(int minRooms) {
        return new SearchCriteria(null, null, null, null, null, null, null,
                LOCATION, PropertyPurpose.RENT, null, minRooms, null, null, null, null, null);
    }

    private static SearchCriteria radius() {
        return new SearchCriteria(null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                new BigDecimal("33.558889"), new BigDecimal("36.056944"), new BigDecimal("10"));
    }

    private static SearchCriteria window() {
        return new SearchCriteria(null, null, null, null,
                Instant.parse("2026-10-01T14:00:00Z"), Instant.parse("2026-10-04T10:00:00Z"), null);
    }

    @Test
    void legacyCriteria_usesTheListingRestrictedCatalogForm() {
        catalogAnswers(List.of(ListingSummary.class.cast(summary())));
        assertThat(matcher.matches(legacy(), LISTING, PROVIDER)).isTrue();
        verify(catalogSearchPort).searchByCriteriaRestrictedToListings(
                any(SearchCriteria.class), eq(Set.of(LISTING)), any(Pageable.class));
    }

    @Test
    void textQuery_usesTheFullTextRestrictedForm() {
        SearchCriteria text = new SearchCriteria("\"sea view\" jeddah", null, null, null);
        catalogAnswers(List.of(summary()));
        assertThat(matcher.matches(text, LISTING, PROVIDER)).isTrue();
        verify(catalogSearchPort).searchFullTextRestrictedToListings(
                eq("\"sea view\" jeddah"), eq(Set.of(LISTING)), any(Pageable.class));
    }

    @Test
    void legacyCriteria_noCatalogMatch_isAMiss() {
        catalogAnswers(List.of());
        assertThat(matcher.matches(legacy(), LISTING, PROVIDER)).isFalse();
    }

    @Test
    void propertyCriteria_composesFacetSetAndCatalogSide() {
        when(geoLookupPort.findSelfAndDescendants(LOCATION)).thenReturn(Set.of(LOCATION));
        when(realestatePropertyFilterPort.findListingIdsMatching(any(PropertyCriteria.class)))
                .thenReturn(Set.of(LISTING));
        catalogAnswers(List.of(summary()));

        assertThat(matcher.matches(property(2), LISTING, PROVIDER)).isTrue();

        var criteriaCaptor = org.mockito.ArgumentCaptor.forClass(PropertyCriteria.class);
        verify(realestatePropertyFilterPort).findListingIdsMatching(criteriaCaptor.capture());
        assertThat(criteriaCaptor.getValue().locationIds()).containsExactly(LOCATION);
        assertThat(criteriaCaptor.getValue().minRooms()).isEqualTo(2);
        verify(catalogSearchPort).searchByCriteriaRestrictedToListings(
                any(SearchCriteria.class), eq(Set.of(LISTING)), any(Pageable.class));
    }

    @Test
    void propertyCriteria_listingOutsideTheFacetSet_isAMiss_withoutAnyCatalogQuery() {
        when(geoLookupPort.findSelfAndDescendants(LOCATION)).thenReturn(Set.of(LOCATION));
        when(realestatePropertyFilterPort.findListingIdsMatching(any(PropertyCriteria.class)))
                .thenReturn(Set.of(UUID.randomUUID())); // someone else's listing

        assertThat(matcher.matches(property(2), LISTING, PROVIDER)).isFalse();
        verify(catalogSearchPort, never()).searchByCriteriaRestrictedToListings(
                any(SearchCriteria.class), anySet(), any(Pageable.class));
    }

    @Test
    void radiusCriteria_usesTheRadiusSet() {
        when(realestatePropertyFilterPort.findListingIdsWithinRadius(
                new BigDecimal("33.558889"), new BigDecimal("36.056944"), 10_000L))
                .thenReturn(Set.of(LISTING));
        catalogAnswers(List.of(summary()));

        assertThat(matcher.matches(radius(), LISTING, PROVIDER)).isTrue();
        verify(realestatePropertyFilterPort).findListingIdsWithinRadius(
                new BigDecimal("33.558889"), new BigDecimal("36.056944"), 10_000L);
    }

    @Test
    void windowedCriteria_providerWithoutAnAvailableSlot_isAMiss() {
        when(availabilityLookupPort.findAvailableProviderIds(
                Instant.parse("2026-10-01T14:00:00Z"), Instant.parse("2026-10-04T10:00:00Z")))
                .thenReturn(Set.of(UUID.randomUUID())); // not our provider

        assertThat(matcher.matches(window(), LISTING, PROVIDER)).isFalse();
        verify(catalogSearchPort, never()).searchByCriteriaRestrictedToListings(
                any(SearchCriteria.class), anySet(), any(Pageable.class));
    }

    @Test
    void windowedCriteria_availableProvider_proceedsToTheCatalogSide() {
        when(availabilityLookupPort.findAvailableProviderIds(
                Instant.parse("2026-10-01T14:00:00Z"), Instant.parse("2026-10-04T10:00:00Z")))
                .thenReturn(Set.of(PROVIDER));
        catalogAnswers(List.of(summary()));

        assertThat(matcher.matches(window(), LISTING, PROVIDER)).isTrue();
    }

    private static ListingSummary summary() {
        return new ListingSummary(LISTING, "title", "stay", new java.math.BigDecimal("10.00"),
                null, null);
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
