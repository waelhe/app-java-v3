package com.marketplace.ai;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketplaceSearchToolsTest {

    private static final String USER_ID = "11111111-1111-1111-1111-111111111111";

    @Test
    void searchesThroughUnifiedSearchPortWithServerControlledPageSize() {
        MarketplaceSearchPort port = mock(MarketplaceSearchPort.class);
        ListingSummary listing = new ListingSummary(
                UUID.randomUUID(), "Laptop", "electronics", null, "EUR", "Provider", null, 0L);
        when(port.search(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new PagedResponse<>(List.of(listing), 0, 5, 1, 1, true));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port);
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
    }

    @Test
    void rejectsMissingUserContext() {
        MarketplaceSearchTools tools = new MarketplaceSearchTools(mock(MarketplaceSearchPort.class));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools.searchListings("laptop", null, null, null, null, null))
                .withMessage("toolContext must not be null");
    }

    @Test
    void browseWithoutTextUsesSameSearchOrchestrationPort() {
        MarketplaceSearchPort port = mock(MarketplaceSearchPort.class);
        ListingSummary listing = new ListingSummary(
                UUID.randomUUID(), "Apartment", "real-estate", null, "EUR", "Provider", null, 0L);
        when(port.search(any(SearchCriteria.class), any(PagedRequest.class)))
                .thenReturn(new PagedResponse<>(List.of(listing), 0, 5, 1, 1, true));

        MarketplaceSearchTools tools = new MarketplaceSearchTools(port);
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
    void rejectsBlankUserContextValue() {
        MarketplaceSearchTools tools = new MarketplaceSearchTools(mock(MarketplaceSearchPort.class));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> tools.searchListings(
                        "laptop", null, null, null, null, new ToolContext(Map.of("userId", " "))))
                .withMessage("toolContext userId must be present");
    }
}
