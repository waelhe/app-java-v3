package com.marketplace.delivery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The courier aggregate's own invariants: coordinates are validated, an
 * assigned courier cannot re-open availability, assignment requires a
 * published location, and finishing respects location freshness (a stale
 * location never auto-returns the courier to the pool).
 */
class DeliveryCourierTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void constructorRequiresAUserId() {
        assertThrows(NullPointerException.class, () -> new DeliveryCourier(null));
        assertEquals(userId, new DeliveryCourier(userId).getUserId());
    }

    @Test
    void freshCourierHasNoLocationAndIsNotAvailable() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        assertFalse(courier.isAvailable());
        assertNull(courier.getLatitude());
        assertNull(courier.getLongitude());
        assertNull(courier.getLocationUpdatedAt());
        assertNull(courier.getCurrentDeliveryRequestId());
        assertNull(courier.getLastAssignedAt());
    }

    @Test
    void updateAvailabilityValidatesCoordinates() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        assertThrows(IllegalArgumentException.class,
                () -> courier.updateAvailability(91, 0, true, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> courier.updateAvailability(0, -181, true, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> courier.updateAvailability(Double.NaN, 0, true, Instant.now()));
    }

    @Test
    void assignedCourierCannotReopenAvailability() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(UUID.randomUUID(), Instant.now());

        assertThrows(IllegalStateException.class,
                () -> courier.updateAvailability(37, -122, true, Instant.now()));
    }

    @Test
    void assignedCourierMayPublishCoordinatesWhileStayingUnavailable() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(UUID.randomUUID(), Instant.now());

        assertDoesNotThrow(() -> courier.updateAvailability(38, -121, false, Instant.now()));
        assertFalse(courier.isAvailable());
    }

    @Test
    void assignRequiresAPublishedLocation() {
        DeliveryCourier courier = new DeliveryCourier(userId);

        assertThrows(IllegalStateException.class,
                () -> courier.assign(UUID.randomUUID(), Instant.now()));
    }

    @Test
    void unavailableCourierCannotBeAssigned() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, false, Instant.now());

        assertThrows(IllegalStateException.class,
                () -> courier.assign(UUID.randomUUID(), Instant.now()));
    }

    @Test
    void assignConsumesTheCourier() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        UUID requestId = UUID.randomUUID();
        Instant assignedAt = Instant.now();

        courier.assign(requestId, assignedAt);

        assertEquals(requestId, courier.getCurrentDeliveryRequestId());
        assertEquals(assignedAt, courier.getLastAssignedAt());
        assertFalse(courier.isAvailable());
    }

    @Test
    void doubleAssignmentIsRejected() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(UUID.randomUUID(), Instant.now());

        assertThrows(IllegalStateException.class,
                () -> courier.assign(UUID.randomUUID(), Instant.now()));
    }

    @Test
    void finishRejectsForeignDeliveries() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        courier.assign(UUID.randomUUID(), Instant.now());

        assertThrows(IllegalStateException.class,
                () -> courier.finish(UUID.randomUUID(), true, Instant.now(), 120));
    }

    @Test
    void finishWithFreshLocationReturnsToThePool() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        UUID requestId = UUID.randomUUID();
        courier.assign(requestId, Instant.now());

        courier.finish(requestId, true, Instant.now(), 120);

        assertNull(courier.getCurrentDeliveryRequestId());
        assertTrue(courier.isAvailable());
    }

    @Test
    void finishWithStaleLocationStaysOutOfThePool() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        UUID requestId = UUID.randomUUID();
        courier.assign(requestId, Instant.now());

        // The location was published 10 minutes ago; the freshness window is 2.
        courier.finish(requestId, true, Instant.now().plusSeconds(600), 120);

        assertNull(courier.getCurrentDeliveryRequestId());
        assertFalse(courier.isAvailable());
    }

    @Test
    void finishWithoutReturnToPoolKeepsTheCourierBusy() {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(37, -122, true, Instant.now());
        UUID requestId = UUID.randomUUID();
        courier.assign(requestId, Instant.now());

        courier.finish(requestId, false, Instant.now(), 120);

        assertFalse(courier.isAvailable());
    }
}
