package com.marketplace.disputes;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.marketplace.shared.ModuleTestConfig.mockBookingParticipantProvider;
import static com.marketplace.shared.ModuleTestConfig.mockCatalogSearchPort;
import static com.marketplace.shared.ModuleTestConfig.mockCurrentUserProvider;
import static com.marketplace.shared.ModuleTestConfig.mockListingPriceProvider;
import static com.marketplace.shared.ModuleTestConfig.mockPaymentIntentLookupPort;
import static com.marketplace.shared.ModuleTestConfig.mockProviderNameResolver;

@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DisputesModuleIntegrationTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        CurrentUserProvider currentUserProvider() {
            return mockCurrentUserProvider();
        }
        @Bean
        BookingParticipantProvider bookingParticipantProvider() {
            return mockBookingParticipantProvider();
        }
        @Bean
        ProviderNameResolver providerNameResolver() {
            return mockProviderNameResolver();
        }
        @Bean
        PaymentIntentLookupPort paymentIntentLookupPort() {
            return mockPaymentIntentLookupPort();
        }
        @Bean
        ListingPriceProvider listingPriceProvider() {
            return mockListingPriceProvider();
        }
        @Bean
        CatalogSearchPort catalogSearchPort() {
            return mockCatalogSearchPort();
        }
    }

    @Test
    void contextLoads() {
    }
}
