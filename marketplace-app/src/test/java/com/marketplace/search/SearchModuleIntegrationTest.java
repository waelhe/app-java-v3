package com.marketplace.search;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.CatalogSearchPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.springframework.data.domain.Page;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class SearchModuleIntegrationTest {

    @MockitoBean
    CatalogSearchPort catalogSearchPort;

    @Autowired
    private SearchService searchService;

    @Test
    void contextLoads() {
    }

    @Test
    void searchAll_returnsEmptyPage() {
        when(catalogSearchPort.listActive(any())).thenReturn(Page.empty());
        var page = searchService.searchAll(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }
}
