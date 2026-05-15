package com.marketplace.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class MessagingModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
