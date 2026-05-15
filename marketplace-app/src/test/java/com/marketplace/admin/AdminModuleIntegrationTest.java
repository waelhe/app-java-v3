package com.marketplace.admin;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@Testcontainers(disabledWithoutDocker = true)
class AdminModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
