package com.marketplace.search;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SearchControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final SearchService service = mock(SearchService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(service))
            .setApiVersionStrategy(apiVersionStrategy())
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();

    @Test
    void searchWithCriteria() throws Exception {
        Page<ListingSummary> page = new PageImpl<>(List.of());
        when(service.search(any(SearchCriteria.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/search")
                        .param("q", "plumber")
                        .param("category", "services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void searchByCategory() throws Exception {
        Page<ListingSummary> page = new PageImpl<>(List.of());
        when(service.searchByCategory(any(), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/search/category/{category}", "services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}
