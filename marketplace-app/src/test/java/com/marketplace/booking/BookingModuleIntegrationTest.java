package com.marketplace.booking;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import com.marketplace.shared.web.ApiVersioningConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class BookingModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ListingPriceProvider listingPriceProvider;

    @Autowired
    private BookingService bookingService;

    @TestConfiguration
    static class TestBeans {
        @Bean
        ApiVersioningConfig apiVersioningConfig() {
            return new ApiVersioningConfig();
        }
    }

    @Test
    void contextLoads() {
    }

    @Test
    void listAllSummaries_returnsEmptyPage() {
        var page = bookingService.listAllSummaries(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }

    @Test
    void listByStatus_returnsEmptyPage() {
        var page = bookingService.listByStatus(BookingStatus.PENDING, Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }
}
