package com.marketplace.booking;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.AvailabilityPort;
import com.marketplace.shared.api.EffectivePricePort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
@WithMockUser
class BookingModuleIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ListingPriceProvider listingPriceProvider;

    @MockitoBean
    AvailabilityPort availabilityPort;

    /**
     * L26: the effective-price seam — PricingService (the port's only
     * implementation) lives in the pricing module, which is NOT part of
     * the booking slice (booking's direct dependencies are shared-only).
     */
    @MockitoBean
    EffectivePricePort effectivePricePort;

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @Autowired
    private BookingService bookingService;

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
