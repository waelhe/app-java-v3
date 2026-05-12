package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SearchServiceTest {

    @Test
    void usesCriteriaSearchWhenPriceFilterProvided() {
        CatalogSearchPort port = mock(CatalogSearchPort.class);
        when(port.searchByCriteria(any(), any())).thenReturn(new PageImpl<>(List.of()));
        SearchService service = new SearchService(port);

        service.search(new SearchCriteria(null, null, BigDecimal.valueOf(10), null), PageRequest.of(0, 20));

        verify(port).searchByCriteria(any(), any());
        verify(port, never()).listActive(any());
    }
}
