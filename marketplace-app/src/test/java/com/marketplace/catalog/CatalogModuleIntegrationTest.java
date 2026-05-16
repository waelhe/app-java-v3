package com.marketplace.catalog;

import com.marketplace.app.testconfig.CurrentUserProviderMockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Import(CurrentUserProviderMockConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class CatalogModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
