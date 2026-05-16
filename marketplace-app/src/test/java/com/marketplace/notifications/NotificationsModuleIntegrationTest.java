package com.marketplace.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketplace.shared.security.CurrentUserProvider;

import static org.mockito.Mockito.mock;

@ApplicationModuleTest
@Testcontainers(disabledWithoutDocker = true)
class NotificationsModuleIntegrationTest {

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        CurrentUserProvider currentUserProvider() {
            return mock(CurrentUserProvider.class);
        }
    }
}
