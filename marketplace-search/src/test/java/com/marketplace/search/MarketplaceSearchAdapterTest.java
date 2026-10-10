package com.marketplace.search;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MarketplaceSearchAdapterTest {

    /**
     * Stage 5: the adapter now also fronts the unified multi-domain
     * orchestration — mocked here; the orchestrator's own guards live in
     * UnifiedSearchServiceTest and the parity journey in the app's real-PG
     * UnifiedSearchParityIntegrationTest.
     */
    private static UnifiedSearchService unified() {
        return org.mockito.Mockito.mock(UnifiedSearchService.class);
    }

    @Test
    void delegatesToCanonicalSearchAndPreservesPageMetadata() {
        SearchService service = mock(SearchService.class);
        SearchCriteria criteria = new SearchCriteria("laptop", "electronics", null, null);
        ListingSummary listing = new ListingSummary(
                UUID.randomUUID(), "Laptop", "electronics", null, "EUR", "Provider", null, 0L);
        when(service.search(any(SearchCriteria.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(listing), PageRequest.of(0, 5), 1));

        PagedResponse<ListingSummary> result =
                new MarketplaceSearchAdapter(service, unified()).search(criteria, PagedRequest.of(0, 5));

        assertThat(result.content()).containsExactly(listing);
        assertThat(result.pageNumber()).isZero();
        assertThat(result.pageSize()).isEqualTo(5);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.last()).isTrue();
        verify(service).search(eq(criteria), argThat(pageable ->
                pageable.getPageNumber() == 0
                        && pageable.getPageSize() == 5
                        && pageable.getSort().isUnsorted()));
    }

    @Test
    void normalizesPublicSortVocabularyBeforeDelegating() {
        SearchService service = mock(SearchService.class);
        SearchCriteria criteria = new SearchCriteria(null, "electronics", null, null);
        when(service.search(any(SearchCriteria.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 5), 0));

        new MarketplaceSearchAdapter(service, unified()).search(
                criteria,
                PagedRequest.of(0, 5, new PagedRequest.Order("price", false)));

        verify(service).search(eq(criteria), argThat(pageable -> {
            var orders = pageable.getSort().stream().toList();
            return orders.size() == 2
                    && orders.get(0).getProperty().equals("priceCents")
                    && orders.get(0).getDirection() == Sort.Direction.ASC
                    && orders.get(1).getProperty().equals("id")
                    && orders.get(1).getDirection() == Sort.Direction.ASC;
        }));
    }

    @Test
    void rejectsUnsupportedSortBeforeCallingSearchService() {
        SearchService service = mock(SearchService.class);
        MarketplaceSearchAdapter adapter = new MarketplaceSearchAdapter(service, unified());

        assertThatThrownBy(() -> adapter.search(
                new SearchCriteria(null, "electronics", null, null),
                PagedRequest.of(0, 5, new PagedRequest.Order("sqlFragment", false))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("unsupported sort property");

        verifyNoInteractions(service);
    }
}
