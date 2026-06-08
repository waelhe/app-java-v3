package com.marketplace.admin;

import com.marketplace.shared.api.ProviderLookupPort;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AdminModuleIntegrationTest {

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    @Test
    void contextLoads() {
    }
}
