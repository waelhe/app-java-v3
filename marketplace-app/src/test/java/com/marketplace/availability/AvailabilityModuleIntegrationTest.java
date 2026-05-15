package com.marketplace.availability;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class AvailabilityModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
