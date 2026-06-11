package com.marketplace.admin;

import com.marketplace.shared.api.ProviderLookupPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketplace.shared.web.ApiVersioningConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AdminModuleIntegrationTest {

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    @Autowired
    private RevisionService revisionService;

    @TestConfiguration
    static class TestBeans {
        @Bean
        ApiVersioningConfig apiVersioningConfig() {
            return new ApiVersioningConfig();
        }
    }

    @Test
    void contextLoads() {
    }

    @Test
    void getEntityNames_returnsEntities() {
        var names = revisionService.getEntityNames();
        assertThat(names).isNotEmpty();
    }

    @Test
    void resolveEntityClass_returnsClassForKnownName() {
        var names = revisionService.getEntityNames();
        var firstName = names.iterator().next();
        var clazz = revisionService.resolveEntityClass(firstName);
        assertThat(clazz).isNotNull();
    }
}
