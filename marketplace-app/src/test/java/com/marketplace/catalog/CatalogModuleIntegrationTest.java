package com.marketplace.catalog;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;
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

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class CatalogModuleIntegrationTest {

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

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ProviderNameResolver providerNameResolver;

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    // L31: CatalogController embeds the property block through
    // PropertyDetailsPort — the realestate module is outside this slice
    // (the full-context integration tests cover the real adapter).
    @MockitoBean
    com.marketplace.shared.api.PropertyDetailsPort propertyDetailsPort;

    // L38: CatalogController composes the completeness score through
    // MediaLookupPort — the media module is outside this slice too (the
    // same house pattern; the full-context integration tests cover the
    // real MediaLookupAdapter).
    @MockitoBean
    com.marketplace.shared.api.MediaLookupPort mediaLookupPort;

    // L39: ListingSeoService resolves the JSON-LD address chain through
    // GeoLookupPort — the geo module is outside this slice exactly like
    // the two ports above (the same house pattern; the full-context
    // SeoIntegrationTest covers the real cached-tree adapter). Without
    // this mock the context boot fails on ListingSeoService's constructor
    // — the module slice boots catalog + shared only, never geo.
    @MockitoBean
    com.marketplace.shared.api.GeoLookupPort geoLookupPort;

    /**
     * L33: the catalog lifecycle's Clock — the production bean lives in
     * platform-infra's ClockConfig, which this slice does not scan (the
     * ClockConfig javadoc's own "tests override the bean" pattern; the
     * slices that DO load infra keep the production bean — one Clock per
     * context, never two).
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class ClockBean {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    @Autowired
    private CatalogService catalogService;

    @Test
    void contextLoads() {
    }

    @Test
    void listActiveSummary_returnsEmptyPage() {
        var page = catalogService.listActiveSummary(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }
}
