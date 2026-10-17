package com.marketplace.delivery;

import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The courier-availability seam: only a verified provider may join the
 * automatic dispatch pool, the row is created on first opt-in, and the
 * response is the persisted projection.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryCourierServiceTest {

    @Mock private DeliveryCourierRepository couriers;
    @Mock private ProviderLookupPort providers;

    private DeliveryCourierService service(DeliveryProperties properties) {
        return new DeliveryCourierService(properties, couriers, providers);
    }

    private DeliveryProperties enabled() {
        return new DeliveryProperties(true, "SAR", 100, 50, 20_000, 900,
                "https://routing.example.invalid", 50, 25, 120, 25);
    }

    @Test
    void disabledFeatureRefusesTheOptIn() {
        assertThrows(ServiceUnavailableException.class, () -> service(
                new DeliveryProperties(false, "SAR", 0, 0, 0, 900, "", 0, 0, 120, 25))
                .updateAvailability(UUID.randomUUID(),
                        new CourierAvailabilityRequest(37.0, -122.0, true)));
    }

    @Test
    void unverifiedProviderIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(providers.findByUserId(userId)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "Courier", "PENDING", userId)));

        assertThrows(ResourceNotFoundException.class, () -> service(enabled()).updateAvailability(
                userId, new CourierAvailabilityRequest(37.0, -122.0, true)));
    }

    @Test
    void providerWithoutProfileIsNotFound() {
        UUID userId = UUID.randomUUID();
        when(providers.findByUserId(userId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service(enabled()).updateAvailability(
                userId, new CourierAvailabilityRequest(37.0, -122.0, true)));
    }

    @Test
    void firstOptInCreatesTheCourierRow() {
        UUID userId = UUID.randomUUID();
        when(providers.findByUserId(userId)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "Courier", "VERIFIED", userId)));
        when(couriers.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());
        when(couriers.save(org.mockito.ArgumentMatchers.any(DeliveryCourier.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DeliveryCourierResponse response = service(enabled()).updateAvailability(
                userId, new CourierAvailabilityRequest(37.0, -122.0, true));

        assertEquals(userId, response.userId());
        assertTrue(response.available());
        assertEquals(37.0, response.latitude());
        assertEquals(-122.0, response.longitude());
        assertNotNull(response.locationUpdatedAt());
        verify(couriers).save(org.mockito.ArgumentMatchers.any(DeliveryCourier.class));
    }

    @Test
    void subsequentOptInUpdatesTheExistingRow() {
        UUID userId = UUID.randomUUID();
        DeliveryCourier existing = new DeliveryCourier(userId);
        existing.updateAvailability(37.0, -122.0, true, Instant.now().minusSeconds(60));
        when(providers.findByUserId(userId)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "Courier", "VERIFIED", userId)));
        when(couriers.findByUserIdForUpdate(userId)).thenReturn(Optional.of(existing));
        when(couriers.save(existing)).thenReturn(existing);

        DeliveryCourierResponse response = service(enabled()).updateAvailability(
                userId, new CourierAvailabilityRequest(38.0, -121.0, false));

        assertFalse(response.available());
        assertEquals(38.0, response.latitude());
        assertEquals(-121.0, response.longitude());
    }
}
