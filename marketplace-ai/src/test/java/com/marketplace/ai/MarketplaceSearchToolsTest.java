package com.marketplace.ai;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceSearchToolsTest {

    @Test
    void searchesThroughSharedCatalogPortWithServerControlledPageSize() {
        CatalogSearchPort port = mock(CatalogSearchPort.class);
        ListingSummary listing = new ListingSummary(
                java.util.UUID.randomUUID(), "Laptop", "electronics",
                null, "EUR", "Provider", null, 0L);
        when(port.searchFullText(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new PagedResponse<>(List.of(listing), 0, 5, 1, 1, true));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port);
        MarketplaceSearchTools.MarketplaceSearchResult result =
                tools.searchListings("laptop", null, null, null, null,
                        new ToolContext(Map.of("userId", "11111111-1111-1111-1111-111111111111")));

        assertThat(result.listings()).containsExactly(listing);
        assertThat(result.totalMatches()).isEqualTo(1L);
        verify(port).searchFullText(any(SearchCriteria.class),
                org.mockito.ArgumentMatchers.argThat(
                        request -> request.page() == 0 && request.size() == 5));
    }

    @Test
    void requiresNonModelUserContext() {
        MarketplaceSearchTools tools = new MarketplaceSearchTools(mock(CatalogSearchPort.class));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools.searchListings("laptop", null, null, null, null, null))
                .withMessage("toolContext must not be null");
    }

    @Test
    void browseWithoutTextUsesCriteriaPort() {
        CatalogSearchPort port = mock(CatalogSearchPort.class);
        ListingSummary listing = new ListingSummary(
                java.util.UUID.randomUUID(), "Apartment", "real-estate",
                null, "EUR", "Provider", null, 0L);
        when(port.searchByCriteria(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new PagedResponse<>(List.of(listing), 0, 5, 1, 1, true));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port);
        MarketplaceSearchTools.MarketplaceSearchResult result =
                tools.searchListings(null, "real-estate", null, null, null,
                        new ToolContext(Map.of("userId", "11111111-1111-1111-1111-111111111111")));

        assertThat(result.listings()).containsExactly(listing);
        verify(port).searchByCriteria(any(SearchCriteria.class),
                org.mockito.ArgumentMatchers.argThat(
                        request -> request.page() == 0 && request.size() == 5));
    }
}

