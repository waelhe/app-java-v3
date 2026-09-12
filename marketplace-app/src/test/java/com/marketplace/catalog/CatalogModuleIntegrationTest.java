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
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class CatalogModuleIntegrationTest {

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
