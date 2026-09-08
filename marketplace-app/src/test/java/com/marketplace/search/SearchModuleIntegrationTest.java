package com.marketplace.search;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.AvailabilityLookupPort;
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

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

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

    // L27: the search slice's cross-module seam grew a second port — the
    // availability lookup is mocked at the slice boundary exactly like the
    // catalog port (the real adapter lives in the availability module and
    // joins in the full-context integration test).
    @MockitoBean
    AvailabilityLookupPort availabilityLookupPort;

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

    @Test
    void searchWithWindow_resolvesTheWhitelistThenQueriesTheRestrictedCatalog() {
        UUID availableProvider = UUID.randomUUID();
        when(availabilityLookupPort.findAvailableProviderIds(any(), any()))
                .thenReturn(Set.of(availableProvider));
        when(catalogSearchPort.searchByCriteriaRestricted(any(), any(), any()))
                .thenReturn(Page.empty());

        var page = searchService.search(
                new com.marketplace.shared.api.SearchCriteria(
                        null, null, null, null,
                        Instant.parse("2026-10-05T10:00:00Z"),
                        Instant.parse("2026-10-08T10:00:00Z")),
                Pageable.ofSize(10));

        assertThat(page).isEmpty();
        org.mockito.Mockito.verify(availabilityLookupPort)
                .findAvailableProviderIds(Instant.parse("2026-10-05T10:00:00Z"),
                        Instant.parse("2026-10-08T10:00:00Z"));
        org.mockito.Mockito.verify(catalogSearchPort)
                .searchByCriteriaRestricted(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(Set.of(availableProvider)),
                        any());
    }
}
