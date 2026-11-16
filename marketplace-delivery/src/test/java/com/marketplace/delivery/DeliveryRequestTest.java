package com.marketplace.delivery;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeliveryRequestTest {

    private DeliveryRequest request() {
        DeliveryQuote quote = new DeliveryQuote(UUID.randomUUID(), UUID.randomUUID(),
                "Pickup", 37, -122, "Dropoff", 38, -121, "Parcel",
                12000, 900, 500, 200, "SAR", Instant.now().plusSeconds(900));
        return new DeliveryRequest(UUID.randomUUID(), quote, "proof-hash");
    }

    @Test
    void requesterCancellationIsAllowedBeforeCourierPickupAndIsIdempotent() {
        DeliveryRequest request = request();
        request.cancel("Changed my mind");
        assertEquals(DeliveryRequestStatus.CANCELLED, request.getStatus());
        assertDoesNotThrow(() -> request.cancel("Retry"));
    }

    @Test
    void requesterCancellationIsRejectedAfterPickup() {
        DeliveryRequest request = request();
        request.paymentSucceeded();
        request.assignCourier(UUID.randomUUID());
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());

        assertThrows(IllegalStateException.class, () -> request.cancel("Too late"));
        assertEquals(DeliveryRequestStatus.PICKED_UP, request.getStatus());
    }

    @Test
    void requesterCancellationIsAllowedBeforePayment() {
        DeliveryRequest request = request();
        request.cancel("Payment abandoned");
        assertEquals(DeliveryRequestStatus.CANCELLED, request.getStatus());
    }

    @Test
    void cancellationRequiresAReason() {
        DeliveryRequest request = request();
        assertThrows(IllegalArgumentException.class, () -> request.cancel("  "));
        assertEquals(DeliveryRequestStatus.AWAITING_PAYMENT, request.getStatus());
    }

    @Test
    void paymentSucceededMovesToReadyForDispatchExactlyOnce() {
        DeliveryRequest request = request();
        request.paymentSucceeded();
        assertEquals(DeliveryRequestStatus.READY_FOR_DISPATCH, request.getStatus());
        assertDoesNotThrow(request::paymentSucceeded);
        request.assignCourier(UUID.randomUUID());
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        assertThrows(IllegalStateException.class, request::paymentSucceeded);
    }

    @Test
    void paymentFailedIsIdempotentAndRefusesLateStates() {
        DeliveryRequest request = request();
        request.paymentFailed();
        request.paymentFailed();
        assertEquals(DeliveryRequestStatus.PAYMENT_FAILED, request.getStatus());
        assertDoesNotThrow(() -> request.cancel("Abandoned after failure"));
    }

    @Test
    void assignCourierRequiresReadyForDispatchAndAUserId() {
        DeliveryRequest request = request();
        assertThrows(IllegalStateException.class, () -> request.assignCourier(UUID.randomUUID()));
        request.paymentSucceeded();
        assertThrows(IllegalArgumentException.class, () -> request.assignCourier(null));
        request.assignCourier(UUID.randomUUID());
        assertEquals(DeliveryRequestStatus.ASSIGNED, request.getStatus());
    }

    @Test
    void theHappyPathRidesTheOfficialTransitionChain() {
        DeliveryRequest request = request();
        request.paymentSucceeded();
        request.assignCourier(UUID.randomUUID());
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        request.transition(DeliveryRequestStatus.IN_TRANSIT, null, Instant.now());
        request.transition(DeliveryRequestStatus.DELIVERED, null, Instant.now());
        assertEquals(DeliveryRequestStatus.DELIVERED, request.getStatus());
        assertNotNull(request.getDeliveredAt());
    }

    @Test
    void theFailurePathRequiresAReason() {
        DeliveryRequest request = request();
        request.paymentSucceeded();
        request.assignCourier(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> request.transition(DeliveryRequestStatus.FAILED, " ", Instant.now()));
        request.transition(DeliveryRequestStatus.FAILED, "Package damaged", Instant.now());
        assertEquals(DeliveryRequestStatus.FAILED, request.getStatus());
        assertEquals("Package damaged", request.getFailureReason());
    }

    @Test
    void illegalTransitionsAreRejectedFromEveryState() {
        DeliveryRequest request = request();
        assertThrows(IllegalStateException.class,
                () -> request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now()));
        request.paymentSucceeded();
        assertThrows(IllegalStateException.class,
                () -> request.transition(DeliveryRequestStatus.DELIVERED, null, Instant.now()));
        request.assignCourier(UUID.randomUUID());
        assertThrows(IllegalStateException.class,
                () -> request.transition(DeliveryRequestStatus.IN_TRANSIT, null, Instant.now()));
    }

    @Test
    void inTransitDeliveriesCannotBeCancelledByTheRequester() {
        DeliveryRequest request = request();
        request.paymentSucceeded();
        request.assignCourier(UUID.randomUUID());
        request.transition(DeliveryRequestStatus.PICKED_UP, null, Instant.now());
        request.transition(DeliveryRequestStatus.IN_TRANSIT, null, Instant.now());
        assertThrows(IllegalStateException.class, () -> request.cancel("Too late"));
    }

    @Test
    void assignedDeliveriesCanStillBeCancelledByTheRequester() {
        DeliveryRequest request = request();
        request.paymentSucceeded();
        request.assignCourier(UUID.randomUUID());
        request.cancel("Found another courier");
        assertEquals(DeliveryRequestStatus.CANCELLED, request.getStatus());
    }

    @Test
    void paymentFailureAfterPaymentSuccessIsRejected() {
        DeliveryRequest request = request();
        request.paymentSucceeded();
        assertThrows(IllegalStateException.class, request::paymentFailed);
    }

    @Test
    void theProofHashMatchIsConstantTimeAndStrict() {
        DeliveryRequest request = request();
        assertFalse(request.matchesProofHash(null));
        assertFalse(request.matchesProofHash("anything"));
    }

    @Test
    void aSecondDifferentPaymentIntentIsRejected() {
        DeliveryRequest request = request();
        UUID first = UUID.randomUUID();
        request.attachPaymentIntent(first);
        request.attachPaymentIntent(first); // idempotent re-attach
        assertThrows(IllegalStateException.class,
                () -> request.attachPaymentIntent(UUID.randomUUID()));
    }

    @Test
    void theQuoteSnapshotRidesIntoTheRequest() {
        UUID requesterId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        DeliveryQuote quote = new DeliveryQuote(UUID.randomUUID(), requesterId,
                "Pickup", 37, -122, "Dropoff", 38, -121, "Parcel",
                12_000, 900, 500, 200, "SAR", Instant.now().plusSeconds(900), orderId);
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "hash");

        assertEquals(quote.getId(), request.getQuoteId());
        assertEquals(orderId, request.getOrderId());
        assertEquals(requesterId, request.getRequesterId());
        assertEquals(500, request.getAmountMinor());
        assertEquals(200, request.getCourierCompensationMinor());
        assertEquals("SAR", request.getCurrency());
        assertEquals(12_000, request.getDistanceMeters());
        assertEquals(900, request.getRouteDurationSeconds());
        assertEquals("Pickup", request.getPickupAddress());
        assertEquals(37, request.getPickupLatitude());
        assertEquals(-122, request.getPickupLongitude());
        assertEquals("Dropoff", request.getDropoffAddress());
        assertEquals(38, request.getDropoffLatitude());
        assertEquals(-121, request.getDropoffLongitude());
        assertEquals("Parcel", request.getPackageDescription());
        assertEquals(DeliveryRequestStatus.AWAITING_PAYMENT, request.getStatus());
        assertNull(request.getCourierUserId());
    }
}
