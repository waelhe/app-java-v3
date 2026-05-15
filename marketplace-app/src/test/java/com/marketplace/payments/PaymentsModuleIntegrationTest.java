package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class PaymentsModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
