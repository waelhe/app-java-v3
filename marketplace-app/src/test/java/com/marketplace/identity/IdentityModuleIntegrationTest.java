package com.marketplace.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;

import static org.mockito.Mockito.mock;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class IdentityModuleIntegrationTest {

    @Test
    void contextLoads() {
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        CurrentUserProvider currentUserProvider() { return mock(CurrentUserProvider.class); }
        @Bean
        BookingParticipantProvider bookingParticipantProvider() { return mock(BookingParticipantProvider.class); }
        @Bean
        CatalogSearchPort catalogSearchPort() { return mock(CatalogSearchPort.class); }
        @Bean
        ListingPriceProvider listingPriceProvider() { return mock(ListingPriceProvider.class); }
        @Bean
        ProviderNameResolver providerNameResolver() { return mock(ProviderNameResolver.class); }
    }
}
