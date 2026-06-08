package com.marketplace.provider;

import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.web.ApiVersioningConfig;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

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
}
