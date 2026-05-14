package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PagedResponseTest {

    @Test
    void of_createsFromPage() {
        Page<String> page = new PageImpl<>(List.of("a", "b"));

        PagedResponse<String> response = PagedResponse.of(page);

        assertEquals(2, response.content().size());
        assertEquals("a", response.content().get(0));
        assertEquals(0, response.pageNumber());
        assertEquals(2, response.totalElements());
        assertEquals(1, response.totalPages());
        assertTrue(response.last());
    }

    @Test
    void of_emptyPage() {
        Page<String> page = new PageImpl<>(List.of());

        PagedResponse<String> response = PagedResponse.of(page);

        assertTrue(response.content().isEmpty());
        assertEquals(0, response.totalElements());
    }
}
