package com.marketplace.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class NotificationsModuleIntegrationTest {

    @Test
    void contextLoads() {
    }
}
