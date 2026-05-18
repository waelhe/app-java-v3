package com.marketplace.shared;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ProviderNameResolver;
import com.marketplace.shared.security.CurrentUserProvider;

import static org.mockito.Mockito.mock;

/**
 * Shared mock factory for port interfaces used across module integration tests.
 * Each module test configures its own inner {@code @TestConfiguration} and
 * delegates to these factory methods.
 */
public final class ModuleTestConfig {

    private ModuleTestConfig() {}

    public static CurrentUserProvider mockCurrentUserProvider() {
        return mock(CurrentUserProvider.class);
    }

    public static BookingParticipantProvider mockBookingParticipantProvider() {
        return mock(BookingParticipantProvider.class);
    }

    public static ProviderNameResolver mockProviderNameResolver() {
        return mock(ProviderNameResolver.class);
    }

    public static PaymentIntentLookupPort mockPaymentIntentLookupPort() {
        return mock(PaymentIntentLookupPort.class);
    }

    public static ListingPriceProvider mockListingPriceProvider() {
        return mock(ListingPriceProvider.class);
    }

    public static CatalogSearchPort mockCatalogSearchPort() {
        return mock(CatalogSearchPort.class);
    }
}
