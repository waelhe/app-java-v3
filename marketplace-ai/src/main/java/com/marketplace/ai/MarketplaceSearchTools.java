package com.marketplace.ai;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public final class MarketplaceSearchTools {

    private static final int TOOL_PAGE_SIZE = 5;
    private final CatalogSearchPort catalogSearchPort;

    public MarketplaceSearchTools(CatalogSearchPort catalogSearchPort) {
        this.catalogSearchPort = Objects.requireNonNull(
                catalogSearchPort, "catalogSearchPort must not be null");
    }

    @Tool(
            name = "search_marketplace_listings",
            description = "Search public marketplace listings by natural-language query and optional category or price filters. Use this when the user asks to find public marketplace listings."
    )
    public MarketplaceSearchResult searchListings(
            @ToolParam(description = "Free-text search query. Leave blank for browse/filter searches.", required = false)
            String query,
            @ToolParam(description = "Exact marketplace category, when explicitly known.", required = false)
            String category,
            @ToolParam(description = "Minimum price in the listing currency, when explicitly requested.", required = false)
            BigDecimal minPrice,
            @ToolParam(description = "Maximum price in the listing currency, when explicitly requested.", required = false)
            BigDecimal maxPrice,
            @ToolParam(description = "Minimum guest capacity, when explicitly requested.", required = false)
            Integer guests,
            ToolContext toolContext) {

        requireUserContext(toolContext);

        String normalizedQuery = query == null ? null : query.trim();
        String normalizedCategory = category == null ? null : category.trim();

        SearchCriteria criteria = new SearchCriteria(
                normalizedQuery == null || normalizedQuery.isBlank() ? null : normalizedQuery,
                normalizedCategory == null || normalizedCategory.isBlank() ? null : normalizedCategory,
                minPrice, maxPrice, null, null, guests);

        PagedRequest request = PagedRequest.of(0, TOOL_PAGE_SIZE);
        PagedResponse<ListingSummary> page =
                criteria.query() != null && !criteria.query().isBlank()
                        ? catalogSearchPort.searchFullText(criteria, request)
                        : catalogSearchPort.searchByCriteria(criteria, request);

        return new MarketplaceSearchResult(page.content(), page.totalElements());
    }

    private static void requireUserContext(ToolContext toolContext) {
        // Spring AI's own convention (MethodToolCallback, v2.0.1 measured):
        // org.springframework.util.Assert throws IllegalArgumentException for
        // argument validation — the same exception the framework's
        // validateToolContextSupport throws when a tool method requires a
        // ToolContext and none is provided.
        Assert.notNull(toolContext, "toolContext must not be null");
        Object userId = toolContext.getContext().get("userId");
        if (userId == null || userId.toString().isBlank()) {
            throw new IllegalArgumentException("toolContext userId must be present");
        }
    }

    public record MarketplaceSearchResult(List<ListingSummary> listings, long totalMatches) {}
}

