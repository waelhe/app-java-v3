package com.marketplace.app.testconfig;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;

import com.marketplace.shared.security.CurrentUserProvider;

import static org.mockito.Mockito.mock;

@TestConfiguration
public class CurrentUserProviderMockConfig {

    @Bean
    public CurrentUserProvider currentUserProvider() {
        return mock(CurrentUserProvider.class);
    }
}
