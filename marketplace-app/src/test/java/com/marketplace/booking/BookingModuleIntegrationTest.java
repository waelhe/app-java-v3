package com.marketplace.booking;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class BookingModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
