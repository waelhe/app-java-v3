package com.marketplace.search;

import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = SearchController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class SearchControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SearchService searchService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void searchWithCriteria_returnsOk() throws Exception {
        when(searchService.search(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/search"))
                .andExpect(status().isOk());
    }

    @Test
    void searchByCategory_returnsOk() throws Exception {
        when(searchService.searchByCategory(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/search/category/{category}", "electronics"))
                .andExpect(status().isOk());
    }

    // ---- L27: the stay window at the HTTP boundary --------------------------

    private static final String CHECK_IN = "2026-09-25T10:00:00Z";
    private static final String CHECK_OUT = "2026-09-28T10:00:00Z";

    @Test
    void validWindow_returnsOkAndReachesTheService() throws Exception {
        when(searchService.search(any(), any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/search")
                        .param("checkIn", CHECK_IN)
                        .param("checkOut", CHECK_OUT))
                .andExpect(status().isOk());

        ArgumentCaptor<SearchCriteria> criteria = ArgumentCaptor.forClass(SearchCriteria.class);
        verify(searchService).search(criteria.capture(), any());
        org.assertj.core.api.Assertions.assertThat(criteria.getValue().hasWindow()).isTrue();
        org.assertj.core.api.Assertions.assertThat(criteria.getValue().checkIn().toString()).isEqualTo(CHECK_IN);
    }

    @Test
    void oneDateOnly_isA400BeforeAnyQuery() throws Exception {
        mockMvc.perform(get("/api/v1/search").param("checkIn", CHECK_IN))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/search").param("checkOut", CHECK_OUT))
                .andExpect(status().isBadRequest());

        verify(searchService, org.mockito.Mockito.never()).search(any(), any());
    }

    @Test
    void reversedWindow_isA400() throws Exception {
        mockMvc.perform(get("/api/v1/search")
                        .param("checkIn", CHECK_OUT)
                        .param("checkOut", CHECK_IN))
                .andExpect(status().isBadRequest());

        verify(searchService, org.mockito.Mockito.never()).search(any(), any());
    }

    @Test
    void equalWindow_isA400() throws Exception {
        // [checkIn, checkOut) with an exclusive end: a zero-length window is
        // rejected at construction — "the two equality and reversal cases are
        // tested" (acceptance criterion 0).
        mockMvc.perform(get("/api/v1/search")
                        .param("checkIn", CHECK_IN)
                        .param("checkOut", CHECK_IN))
                .andExpect(status().isBadRequest());

        verify(searchService, org.mockito.Mockito.never()).search(any(), any());
    }
}
