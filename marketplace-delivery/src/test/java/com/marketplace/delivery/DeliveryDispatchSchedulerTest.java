package com.marketplace.delivery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The automatic dispatch sweep: disabled feature answers nothing, the batch
 * window is honored, the courier shortlist is air-distance pre-sorted then
 * road-routed (air distance never decides, only shortlists), and an
 * uncertain assignment failure is swallowed for the next sweep to
 * reconcile — never escalated into an external effect.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryDispatchSchedulerTest {

    @Mock private DeliveryRequestRepository requests;
    @Mock private DeliveryCourierRepository couriers;
    @Mock private DeliveryRouteProvider routes;
    @Mock private DeliveryAssignmentService assignments;

    private DeliveryDispatchScheduler scheduler(DeliveryProperties properties) {
        return new DeliveryDispatchScheduler(properties, requests, couriers, routes, assignments);
    }

    private DeliveryProperties enabled() {
        return new DeliveryProperties(true, "SAR", 100, 50, 20_000, 900,
                "https://routing.example.invalid", 50, 25, 120, 25, 3L);
    }

    private DeliveryRequest pendingRequest(UUID id) {
        DeliveryQuote quote = new DeliveryQuote(id, UUID.randomUUID(),
                "Pickup", 37, -122, "Dropoff", 38, -121, "Parcel",
                12_000, 900, 500, 200, "SAR", Instant.now().plusSeconds(900));
        DeliveryRequest request = new DeliveryRequest(id, quote, "proof-hash");
        request.paymentSucceeded();
        return request;
    }

    private DeliveryCourier courierAt(UUID userId, double latitude, double longitude) {
        DeliveryCourier courier = new DeliveryCourier(userId);
        courier.updateAvailability(latitude, longitude, true, Instant.now());
        return courier;
    }

    @Test
    void disabledFeatureNeverSweeps() {
        DeliveryProperties disabled = new DeliveryProperties(false, "SAR", 0, 0, 0, 900,
                "", 0, 0, 120, 25, 3L);

        scheduler(disabled).dispatchReadyRequests();

        verifyNoInteractions(requests, couriers, routes, assignments);
    }

    @Test
    void noPendingRequestsEndsTheSweep() {
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        scheduler(enabled()).dispatchReadyRequests();

        verifyNoInteractions(couriers, routes, assignments);
    }

    @Test
    void noAvailableCouriersStopsTheSweep() {
        UUID requestId = UUID.randomUUID();
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRequest(requestId))));
        when(couriers.findAvailableForDispatch(any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        scheduler(enabled()).dispatchReadyRequests();

        verifyNoInteractions(routes, assignments);
    }

    @Test
    void nearestCourierByRoadDurationWinsTheAssignment() {
        UUID requestId = UUID.randomUUID();
        UUID airNearRoadSlow = UUID.randomUUID();
        UUID airFarRoadFast = UUID.randomUUID();
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRequest(requestId))));
        // The air-distance shortlist order deliberately contradicts the road
        // duration order: the far-by-air courier is faster by road.
        when(couriers.findAvailableForDispatch(any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        courierAt(airNearRoadSlow, 37.5, -122.0),
                        courierAt(airFarRoadFast, 40.0, -120.0))));
        when(routes.route(37.5, -122.0, 37, -122)).thenReturn(new DeliveryRouteMetrics(1_000, 1_800));
        when(routes.route(40.0, -120.0, 37, -122)).thenReturn(new DeliveryRouteMetrics(9_000, 900));

        scheduler(enabled()).dispatchReadyRequests();

        verify(assignments).assignIfEligible(requestId, airFarRoadFast);
    }

    @Test
    void couriersWithoutCoordinatesAreSkippedNotFatal() {
        UUID requestId = UUID.randomUUID();
        // The repository's availability query already filtered by freshness;
        // the coordinate filter is the scheduler's own defense — a courier
        // row that never published coordinates is skipped, never routed.
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRequest(requestId))));
        when(couriers.findAvailableForDispatch(any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new DeliveryCourier(UUID.randomUUID()))));

        scheduler(enabled()).dispatchReadyRequests();

        verifyNoInteractions(routes, assignments);
    }

    @Test
    void routeBeyondTheServiceAreaDisqualifiesTheCourier() {
        UUID requestId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRequest(requestId))));
        when(couriers.findAvailableForDispatch(any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(courierAt(courierId, 37.0, -122.0))));
        when(routes.route(37.0, -122.0, 37, -122))
                .thenReturn(new DeliveryRouteMetrics(50_000, 3_600));

        scheduler(enabled()).dispatchReadyRequests();

        verifyNoInteractions(assignments);
    }

    @Test
    void routingOutageKeepsTheSweepQueued() {
        UUID requestId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRequest(requestId))));
        when(couriers.findAvailableForDispatch(any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(courierAt(courierId, 37.0, -122.0))));
        when(routes.route(37.0, -122.0, 37, -122))
                .thenThrow(new com.marketplace.shared.api.ServiceUnavailableException("down"));

        scheduler(enabled()).dispatchReadyRequests();

        verifyNoInteractions(assignments);
    }

    @Test
    void anUncertainAssignmentIsRetriedByTheNextSweep() {
        UUID requestId = UUID.randomUUID();
        UUID courierId = UUID.randomUUID();
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingRequest(requestId))));
        when(couriers.findAvailableForDispatch(any(Instant.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(courierAt(courierId, 37.0, -122.0))));
        when(routes.route(37.0, -122.0, 37, -122)).thenReturn(new DeliveryRouteMetrics(1_000, 600));
        when(assignments.assignIfEligible(requestId, courierId))
                .thenThrow(new IllegalStateException("serialization failure"));

        scheduler(enabled()).dispatchReadyRequests();

        // The sweep completes without propagating the transient failure.
        verify(assignments).assignIfEligible(requestId, courierId);
    }

    @Test
    void theSweepHonorsTheConfiguredBatchSize() {
        DeliveryProperties smallBatch = new DeliveryProperties(true, "SAR", 100, 50, 20_000, 900,
                "https://routing.example.invalid", 50, 25, 120, 2, 3L);
        when(requests.findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        scheduler(smallBatch).dispatchReadyRequests();

        // The pending-request read is capped at the configured batch size; an
        // empty page ends the sweep before any courier query happens.
        verify(requests).findByStatusOrderByCreatedAtAscIdAsc(
                eq(DeliveryRequestStatus.READY_FOR_DISPATCH), eq(PageRequest.of(0, 2)));
        verifyNoInteractions(couriers);
    }
}
