package com.marketplace.search;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchService searchService;

    @InjectMocks
    private SearchController controller;

    @Test
    void searchWithCriteria_returnsPagedResponse() {
        PageRequest pageable = PageRequest.of(0, 10);
        Page<ListingSummary> page = new PageImpl<>(List.of());
        when(searchService.search(any(SearchCriteria.class), eq(pageable))).thenReturn(page);

        ResponseEntity<PagedResponse<ListingSummary>> result = controller.searchWithCriteria(null, null, null, null, null, null, pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void searchWithCriteria_carriesTheStayWindowToTheService() {
        PageRequest pageable = PageRequest.of(0, 10);
        when(searchService.search(any(SearchCriteria.class), eq(pageable))).thenReturn(new PageImpl<>(List.of()));
        java.time.Instant checkIn = java.time.Instant.parse("2026-10-05T10:00:00Z");
        java.time.Instant checkOut = java.time.Instant.parse("2026-10-08T10:00:00Z");

        controller.searchWithCriteria(null, null, null, null, checkIn, checkOut, pageable);

        org.mockito.ArgumentCaptor<SearchCriteria> captor = org.mockito.ArgumentCaptor.forClass(SearchCriteria.class);
        verify(searchService).search(captor.capture(), eq(pageable));
        assertTrue(captor.getValue().hasWindow());
        assertEquals(checkIn, captor.getValue().checkIn());
        assertEquals(checkOut, captor.getValue().checkOut());
    }

    @Test
    void searchByCategory_returnsPagedResponse() {
        PageRequest pageable = PageRequest.of(0, 10);
        Page<ListingSummary> page = new PageImpl<>(List.of());
        when(searchService.searchByCategory("tech", pageable)).thenReturn(page);

        ResponseEntity<PagedResponse<ListingSummary>> result = controller.searchByCategory("tech", pageable);

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }
}
