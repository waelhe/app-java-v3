package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class IdentityModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
