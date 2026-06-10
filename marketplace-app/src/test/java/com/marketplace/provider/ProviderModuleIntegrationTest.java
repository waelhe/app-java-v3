package com.marketplace.provider;

import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.web.ApiVersioningConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private ProviderService providerService;

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
    void createProvider_persists() {
        var profile = providerService.create("Test Provider", "A test provider", UUID.randomUUID());
        assertThat(profile.getId()).isNotNull();
        assertThat(profile.getDisplayName()).isEqualTo("Test Provider");
    }

    @Test
    void getById_throwsForUnknown() {
        assertThrows(ResourceNotFoundException.class, () -> providerService.getById(UUID.randomUUID()));
    }
}
