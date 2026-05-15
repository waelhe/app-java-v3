package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class PricingModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
