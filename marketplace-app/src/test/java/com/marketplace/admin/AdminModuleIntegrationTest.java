package com.marketplace.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.marketplace.booking.spi.BookingSpi;
import com.marketplace.catalog.spi.CatalogSpi;
import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;

import static org.mockito.Mockito.mock;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AdminModuleIntegrationTest {

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
        @Bean
        BookingSpi bookingSpi() { return mock(BookingSpi.class); }
        @Bean
        CatalogSpi catalogSpi() { return mock(CatalogSpi.class); }
        @Bean
        IdentitySpi identitySpi() { return mock(IdentitySpi.class); }
        @Bean
        PaymentsSpi paymentsSpi() { return mock(PaymentsSpi.class); }
        @Bean
        WebMvcConfigurer apiVersioningConfigurer() {
            return new WebMvcConfigurer() {
                @Override
                public void configureApiVersioning(ApiVersionConfigurer configurer) {
                    configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
                }
            };
        }
    }
}
