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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    /**
     * L35: the module's first persistent shape (SavedSearchService) needs
     * the injectable house clock — the catalog slice's exact pattern
     * (CatalogModuleIntegrationTest.ClockBean): the production bean lives
     * in platform-infra's ClockConfig, which this slice does not scan (a
     * direct @Import of the config class fails in the slice — its
     * instance-method @Bean needs the config-class bean, which the module
     * filter drops — measured in CI round 2). One Clock per context.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class ClockBean {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    @MockitoBean
    CatalogSearchPort catalogSearchPort;

    // L27: the search slice's cross-module seam grew a second port — the
    // availability lookup is mocked at the slice boundary exactly like the
    // catalog port (the real adapter lives in the availability module and
    // joins in the full-context integration test).
    @MockitoBean
    AvailabilityLookupPort availabilityLookupPort;

    // L32: the seam grew the geo port too (the location facet resolves
    // through it) — mocked at the slice boundary exactly like the other
    // two (the real adapter is the geo module, exercised by its own
    // module test and the full-context integration tests).
    @MockitoBean
    com.marketplace.shared.api.GeoLookupPort geoLookupPort;

    // L32: and the realestate filter port (the property facets resolve
    // through it) — same slice-boundary pattern (the real adapter is the
    // realestate module).
    @MockitoBean
    com.marketplace.shared.api.RealestatePropertyFilterPort realestatePropertyFilterPort;

    // L35: the /me surface's identity stitch — the shared-security
    // component is outside this slice (the LeadsController sibling in the
    // messaging module's slice mocks it the same way).
    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

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
