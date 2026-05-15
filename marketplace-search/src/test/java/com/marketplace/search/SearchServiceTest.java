package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {

    private final CatalogSearchPort port = mock(CatalogSearchPort.class);
    private final SearchService service = new SearchService(port);

    @Test
    void usesCriteriaSearchWhenPriceFilterProvided() {
        when(port.searchByCriteria(any(), any())).thenReturn(new PageImpl<>(List.of()));
        service.search(new SearchCriteria(null, null, BigDecimal.valueOf(10), null), PageRequest.of(0, 20));
        verify(port).searchByCriteria(any(), any());
        verify(port, never()).listActive(any());
    }

    @Test
    void usesFullTextSearchWhenQueryProvided() {
        when(port.searchFullText(anyString(), any())).thenReturn(new PageImpl<>(List.of()));
        service.search(new SearchCriteria("test query", null, null, null), PageRequest.of(0, 20));
        verify(port).searchFullText("test & query", PageRequest.of(0, 20));
    }

    @Test
    void usesCategorySearchWhenCategoryProvided() {
        when(port.listByCategory(anyString(), any())).thenReturn(new PageImpl<>(List.of()));
        service.search(new SearchCriteria(null, "electronics", null, null), PageRequest.of(0, 20));
        verify(port).listByCategory("electronics", PageRequest.of(0, 20));
    }

    @Test
    void usesListActiveWhenNoFilters() {
        when(port.listActive(any())).thenReturn(new PageImpl<>(List.of()));
        service.search(new SearchCriteria(null, null, null, null), PageRequest.of(0, 20));
        verify(port).listActive(PageRequest.of(0, 20));
    }

    @Test
    void searchWithStringDelegatesToCriteriaSearch() {
        when(port.listActive(any())).thenReturn(new PageImpl<>(List.of()));
        service.search(null, null, PageRequest.of(0, 20));
        verify(port).listActive(PageRequest.of(0, 20));
    }

    @Test
    void searchByCategoryDelegatesToListByCategory() {
        when(port.listByCategory(anyString(), any())).thenReturn(new PageImpl<>(List.of()));
        Page<ListingSummary> result = service.searchByCategory("books", PageRequest.of(0, 10));
        verify(port).listByCategory("books", PageRequest.of(0, 10));
        assertNotNull(result);
    }

    @Test
    void searchAllDelegatesToListActive() {
        when(port.listActive(any())).thenReturn(new PageImpl<>(List.of()));
        Page<ListingSummary> result = service.searchAll(PageRequest.of(0, 10));
        verify(port).listActive(PageRequest.of(0, 10));
        assertNotNull(result);
    }

    @Test
    void refresherExecutesSql() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SearchIndexRefresher refresher = new SearchIndexRefresher(jdbc);
        refresher.executeInternal(null);
        verify(jdbc).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_listing_search");
    }
}
