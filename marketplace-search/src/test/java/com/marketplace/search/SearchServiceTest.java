package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    private final CatalogSearchPort port = mock(CatalogSearchPort.class);
    private final SearchService service = new SearchService(port);

    private static Page<ListingSummary> emptyPage() {
        return new PageImpl<>(List.of());
    }

    @Test
    void usesCriteriaSearchWhenPriceFilterProvided() {
        when(port.searchByCriteria(any(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria(null, null, BigDecimal.valueOf(10), null), PageRequest.of(0, 20));

        verify(port).searchByCriteria(any(), any());
        verify(port, never()).listActive(any());
    }

    @Test
    void usesFullTextSearchWhenQueryProvided() {
        when(port.searchFullText(anyString(), any())).thenReturn(emptyPage());

        service.search(new SearchCriteria("hello world", null, null, null), PageRequest.of(0, 10));

        verify(port).searchFullText("hello & world", PageRequest.of(0, 10));
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
}
