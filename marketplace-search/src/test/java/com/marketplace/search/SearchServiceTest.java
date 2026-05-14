package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchServiceTest {

    private final CatalogSearchPort port = mock(CatalogSearchPort.class);
    private SearchService service;

    @BeforeEach
    void setUp() {
        when(port.searchFullText(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(port.searchByCriteria(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(port.listByCategory(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(port.listActive(any())).thenReturn(new PageImpl<>(List.of()));
        service = new SearchService(port);
    }

    @Test
    void usesFullTextSearchWhenQueryProvided() {
        service.search(new SearchCriteria("plumber", null, null, null), PageRequest.of(0, 20));

        verify(port).searchFullText(eq("plumber"), any());
        verify(port, never()).listActive(any());
    }

    @Test
    void usesCriteriaSearchWhenPriceFilterProvided() {
        service.search(new SearchCriteria(null, null, BigDecimal.valueOf(10), null), PageRequest.of(0, 20));

        verify(port).searchByCriteria(any(), any());
        verify(port, never()).listActive(any());
    }

    @Test
    void usesCategorySearchWhenCategoryProvided() {
        service.search(new SearchCriteria(null, "services", null, null), PageRequest.of(0, 20));

        verify(port).listByCategory(eq("services"), any());
        verify(port, never()).listActive(any());
    }

    @Test
    void fallsBackToListActive() {
        service.search(new SearchCriteria(null, null, null, null), PageRequest.of(0, 20));

        verify(port).listActive(any());
    }

    @Test
    void searchByCategoryDelegates() {
        service.searchByCategory("plumbing", PageRequest.of(0, 10));

        verify(port).listByCategory(eq("plumbing"), any());
    }

    @Test
    void searchAllDelegatesToListActive() {
        service.searchAll(PageRequest.of(0, 10));

        verify(port).listActive(any());
    }

    @Test
    void searchWithStringParamsUsesFullText() {
        service.search("plumber", null, PageRequest.of(0, 20));

        verify(port).searchFullText(eq("plumber"), any());
    }

    @Test
    void searchWithBlankQueryUsesCategory() {
        service.search("", "services", PageRequest.of(0, 20));

        verify(port).listByCategory(eq("services"), any());
    }

    @Test
    void searchWithNullQueryAndCategoryUsesListActive() {
        service.search(null, null, PageRequest.of(0, 20));

        verify(port).listActive(any());
    }

    @Test
    void searchWithQueryConvertsToTsQueryFormat() {
        service.search("hello world", null, PageRequest.of(0, 20));

        verify(port).searchFullText(eq("hello & world"), any());
    }
}
