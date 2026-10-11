package com.marketplace.ai;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketplaceSearchToolsTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void searchesThroughUnifiedSearchPortWithServerControlledPageSize() {
        MarketplaceSearchPort port = mock(MarketplaceSearchPort.class);
        GeoLookupPort geo = mock(GeoLookupPort.class);
        ListingSummary listing = new ListingSummary(
                UUID.randomUUID(), "Laptop", "electronics", null, "EUR", "Provider", null, 0L, false);
        when(port.search(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new PagedResponse<>(List.of(listing), 0, 5, 1, 1, true));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port, geo);
        MarketplaceSearchTools.MarketplaceSearchResult result =
                tools.searchListings("laptop", null, null, null, null,
                        new ToolContext(Map.of("userId", USER_ID)));

        assertThat(result.listings()).containsExactly(listing);
        assertThat(result.totalMatches()).isEqualTo(1L);
        verify(port).search(argThat(criteria ->
                        "laptop".equals(criteria.query())
                                && criteria.category() == null),
                argThat(request -> request.page() == 0
                        && request.size() == 5
                        && request.sort().isEmpty()));
        verifyNoInteractions(geo);
    }

    @Test
    void rejectsMissingUserContext() {
        MarketplaceSearchTools tools = new MarketplaceSearchTools(
                mock(MarketplaceSearchPort.class), mock(GeoLookupPort.class));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools.searchListings("laptop", null, null, null, null, null))
                .withMessage("toolContext must not be null");
    }

    @Test
    void browseWithoutTextUsesSameSearchOrchestrationPort() {
        MarketplaceSearchPort port = mock(MarketplaceSearchPort.class);
        GeoLookupPort geo = mock(GeoLookupPort.class);
        ListingSummary listing = new ListingSummary(
                UUID.randomUUID(), "Apartment", "real-estate", null, "EUR", "Provider", null, 0L, false);
        when(port.search(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new PagedResponse<>(List.of(listing), 0, 5, 1, 1, true));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port, geo);
        MarketplaceSearchTools.MarketplaceSearchResult result =
                tools.searchListings(null, "real-estate", null, null, null,
                        new ToolContext(Map.of("userId", USER_ID)));

        assertThat(result.listings()).containsExactly(listing);
        verify(port).search(argThat(criteria ->
                        criteria.query() == null
                                && "real-estate".equals(criteria.category())),
                argThat(request -> request.page() == 0 && request.size() == 5));
    }

    @Test
    void resolvesAnExactArabicLocationAndComposesStructuredRealEstateFilters() {
        MarketplaceSearchPort port = mock(MarketplaceSearchPort.class);
        GeoLookupPort geo = mock(GeoLookupPort.class);
        UUID locationId = UUID.randomUUID();
        when(geo.suggest("قدسيا")).thenReturn(List.of(new GeoLookupPort.GeoNode(
                locationId, null, 3, "قدسيا", "Qudsaya", "qudsaya")));
        when(port.search(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new PagedResponse<>(List.of(), 0, 5, 0, 0, true));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port, geo);
        tools.searchListingsAdvanced(
                "شقة", null, null, null, null,
                "قُدْسيا", PropertyPurpose.RENT, PropertyType.APARTMENT,
                2, 1, 80, null, null, null, null, null, new BigDecimal("4.0"),
                new ToolContext(Map.of("userId", USER_ID)));

        verify(geo).suggest("قدسيا");
        verify(port).search(argThat(criteria ->
                        "شقة".equals(criteria.query())
                                && locationId.equals(criteria.locationId())
                                && criteria.purpose() == PropertyPurpose.RENT
                                && criteria.propertyType() == PropertyType.APARTMENT
                                && Integer.valueOf(2).equals(criteria.minRooms())
                                && Integer.valueOf(1).equals(criteria.minBathrooms())
                                && Integer.valueOf(80).equals(criteria.minAreaM2())
                                && new BigDecimal("4.0").compareTo(criteria.minRating()) == 0),
                argThat(request -> request.page() == 0 && request.size() == 5));
    }

    @Test
    void ambiguousLocationReturnsChoicesAndNeverRunsAnUnrestrictedSearch() {
        MarketplaceSearchPort port = mock(MarketplaceSearchPort.class);
        GeoLookupPort geo = mock(GeoLookupPort.class);
        when(geo.suggest("قدس")).thenReturn(List.of(
                new GeoLookupPort.GeoNode(UUID.randomUUID(), null, 3, "قدسيا القديمة", null, "qudsaya-old"),
                new GeoLookupPort.GeoNode(UUID.randomUUID(), null, 3, "قدسيا الجديدة", null, "qudsaya-new")));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port, geo);
        MarketplaceSearchTools.MarketplaceSearchResult result = tools.searchListingsAdvanced(
                null, null, null, null, null,
                "قدس", null, null, null, null, null,
                null, null, null, null, null, null,
                new ToolContext(Map.of("userId", USER_ID)));

        assertThat(result.listings()).isEmpty();
        assertThat(result.totalMatches()).isZero();
        assertThat(result.clarification()).contains("not unique");
        assertThat(result.locationOptions()).hasSize(2);
        verifyNoInteractions(port);
    }

    @Test
    void rejectsOverlongQueryBeforeCallingSearch() {
        MarketplaceSearchPort port = mock(MarketplaceSearchPort.class);
        MarketplaceSearchTools tools = new MarketplaceSearchTools(port, mock(GeoLookupPort.class));

        assertThatThrownBy(() -> tools.searchListingsAdvanced(
                "x".repeat(201), null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                new ToolContext(Map.of("userId", USER_ID))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("200 Unicode code points");
        verifyNoInteractions(port);
    }

    @Test
    void rejectsBlankUserContextValue() {
        MarketplaceSearchTools tools = new MarketplaceSearchTools(
                mock(MarketplaceSearchPort.class), mock(GeoLookupPort.class));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools.searchListings(
                        "laptop", null, null, null, null, new ToolContext(Map.of("userId", " "))))
                .withMessage("toolContext userId must be present");
    }
}
