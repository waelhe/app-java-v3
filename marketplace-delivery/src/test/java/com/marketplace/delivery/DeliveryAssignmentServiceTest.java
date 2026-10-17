package com.marketplace.delivery;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The automatic courier-claim seam: the assignment runs inside one locked
 * transaction and answers false (never throws) for every ineligible
 * precondition — only a genuinely racy state mutation surfaces as
 * ConflictException for the sweep to log and retry.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryAssignmentServiceTest {

    @Mock private DeliveryRequestRepository requests;
    @Mock private DeliveryCourierRepository couriers;
    @Mock private DeliveryRequestEventRepository events;
    @Mock private ProviderLookupPort providers;

    private final UUID courierUserId = UUID.randomUUID();

    private DeliveryAssignmentService service(DeliveryProperties properties) {
        return new DeliveryAssignmentService(requests, couriers, events, properties, providers);
    }

    private DeliveryProperties enabled() {
        return new DeliveryProperties(true, "SAR", 100, 50, 20_000, 900,
                "https://routing.example.invalid", 50, 25, 120, 25, 3L);
    }

    private DeliveryRequest readyRequest() {
        DeliveryQuote quote = new DeliveryQuote(UUID.randomUUID(), UUID.randomUUID(),
                "Pickup", 37, -122, "Dropoff", 38, -121, "Parcel",
                12_000, 900, 500, 200, "SAR", Instant.now().plusSeconds(900));
        DeliveryRequest request = new DeliveryRequest(UUID.randomUUID(), quote, "proof-hash");
        request.paymentSucceeded();
        return request;
    }

    private DeliveryCourier availableCourier() {
        DeliveryCourier courier = new DeliveryCourier(courierUserId);
        courier.updateAvailability(37.0, -122.0, true, Instant.now());
        return courier;
    }

    private void verifiedProvider() {
        when(providers.findByUserId(courierUserId)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "Courier", "VERIFIED", courierUserId)));
    }

    @Test
    void assignsAnEligibleCourierAndRecordsTheTransition() {
        UUID requestId = UUID.randomUUID();
        DeliveryRequest request = readyRequest();
        DeliveryCourier courier = availableCourier();
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        verifiedProvider();
        when(couriers.findByUserIdForUpdate(courierUserId)).thenReturn(Optional.of(courier));

        boolean assigned = service(enabled()).assignIfEligible(requestId, courierUserId);

        assertTrue(assigned);
        assertEquals(DeliveryRequestStatus.ASSIGNED, request.getStatus());
        assertEquals(courierUserId, request.getCourierUserId());
        assertFalse(courier.isAvailable());
        assertEquals(requestId, courier.getCurrentDeliveryRequestId());
        verify(requests).save(request);
        verify(couriers).save(courier);
        // The audit event is a write-only ledger row (no status accessors) — its
        // persistence alongside the locked mutation is the contract.
        verify(events).save(any(DeliveryRequestEvent.class));
    }

    @Test
    void missingRequestAnswersFalseWithoutSideEffects() {
        when(requests.findByIdForUpdate(any())).thenReturn(Optional.empty());

        assertFalse(service(enabled()).assignIfEligible(UUID.randomUUID(), courierUserId));
        verifyNoInteractions(couriers, events, providers);
    }

    @Test
    void nonDispatchableStatusAnswersFalse() {
        UUID requestId = UUID.randomUUID();
        DeliveryQuote quote = new DeliveryQuote(UUID.randomUUID(), UUID.randomUUID(),
                "Pickup", 37, -122, "Dropoff", 38, -121, "Parcel",
                12_000, 900, 500, 200, "SAR", Instant.now().plusSeconds(900));
        when(requests.findByIdForUpdate(requestId))
                .thenReturn(Optional.of(new DeliveryRequest(requestId, quote, "proof-hash")));

        assertFalse(service(enabled()).assignIfEligible(requestId, courierUserId));
        verifyNoInteractions(couriers, events, providers);
    }

    @Test
    void alreadyAssignedRequestAnswersFalse() {
        UUID requestId = UUID.randomUUID();
        DeliveryRequest request = readyRequest();
        request.assignCourier(UUID.randomUUID());
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

        assertFalse(service(enabled()).assignIfEligible(requestId, courierUserId));
        verifyNoInteractions(couriers, events, providers);
    }

    @Test
    void unverifiedProviderIsRejected() {
        UUID requestId = UUID.randomUUID();
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(readyRequest()));
        when(providers.findByUserId(courierUserId)).thenReturn(Optional.of(
                new ProviderSummary(UUID.randomUUID(), "Courier", "PENDING", courierUserId)));

        assertFalse(service(enabled()).assignIfEligible(requestId, courierUserId));
        verifyNoInteractions(couriers, events);
    }

    @Test
    void providerWithoutProfileIsRejected() {
        UUID requestId = UUID.randomUUID();
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(readyRequest()));
        when(providers.findByUserId(courierUserId)).thenReturn(Optional.empty());

        assertFalse(service(enabled()).assignIfEligible(requestId, courierUserId));
        verifyNoInteractions(couriers, events);
    }

    @Test
    void missingCourierRecordAnswersFalse() {
        UUID requestId = UUID.randomUUID();
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(readyRequest()));
        verifiedProvider();
        when(couriers.findByUserIdForUpdate(courierUserId)).thenReturn(Optional.empty());

        assertFalse(service(enabled()).assignIfEligible(requestId, courierUserId));
        verifyNoInteractions(events);
    }

    @Test
    void busyCourierAnswersFalse() {
        UUID requestId = UUID.randomUUID();
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(readyRequest()));
        verifiedProvider();
        DeliveryCourier busy = availableCourier();
        busy.assign(UUID.randomUUID(), Instant.now());
        when(couriers.findByUserIdForUpdate(courierUserId)).thenReturn(Optional.of(busy));

        assertFalse(service(enabled()).assignIfEligible(requestId, courierUserId));
        verifyNoInteractions(events);
    }

    @Test
    void staleCourierLocationAnswersFalse() {
        UUID requestId = UUID.randomUUID();
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(readyRequest()));
        verifiedProvider();
        DeliveryCourier stale = new DeliveryCourier(courierUserId);
        stale.updateAvailability(37.0, -122.0, true, Instant.now().minusSeconds(600));
        when(couriers.findByUserIdForUpdate(courierUserId)).thenReturn(Optional.of(stale));

        assertFalse(service(enabled()).assignIfEligible(requestId, courierUserId));
        verifyNoInteractions(events);
    }

    @Test
    void racyStateMutationSurfacesAsConflict() {
        UUID requestId = UUID.randomUUID();
        // The race the lock exists for: the entity's own state guard fires
        // between the service's eligibility read and its mutation.
        DeliveryRequest request = spy(readyRequest());
        doThrow(new IllegalStateException("Concurrent assignment won the race"))
                .when(request).assignCourier(courierUserId);
        when(requests.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        verifiedProvider();
        when(couriers.findByUserIdForUpdate(courierUserId)).thenReturn(Optional.of(availableCourier()));

        assertThrows(ConflictException.class,
                () -> service(enabled()).assignIfEligible(requestId, courierUserId));
        verify(requests, never()).save(any());
        verify(couriers, never()).save(any());
        verify(events, never()).save(any());
    }
}
