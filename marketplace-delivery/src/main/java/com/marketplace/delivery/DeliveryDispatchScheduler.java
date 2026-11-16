package com.marketplace.delivery;

import com.marketplace.shared.api.ServiceUnavailableException;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Component
public class DeliveryDispatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatchScheduler.class);

    private final DeliveryProperties properties;
    private final DeliveryRequestRepository requests;
    private final DeliveryCourierRepository couriers;
    private final DeliveryRouteProvider routes;
    private final DeliveryAssignmentService assignments;

    public DeliveryDispatchScheduler(DeliveryProperties properties,
                                     DeliveryRequestRepository requests,
                                     DeliveryCourierRepository couriers,
                                     DeliveryRouteProvider routes,
                                     DeliveryAssignmentService assignments) {
        this.properties = properties;
        this.requests = requests;
        this.couriers = couriers;
        this.routes = routes;
        this.assignments = assignments;
    }

    @Scheduled(fixedDelayString = "${marketplace.delivery.dispatch-interval-ms:10000}")
    @Observed(name = "delivery.dispatch.sweep")
    public void dispatchReadyRequests() {
        if (!properties.enabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant freshAfter = now.minusSeconds(properties.courierLocationMaxAgeSeconds());
        var pending = requests.findByStatusOrderByCreatedAtAscIdAsc(
                DeliveryRequestStatus.READY_FOR_DISPATCH, PageRequest.of(0, properties.dispatchBatchSize()));
        for (DeliveryRequest request : pending.getContent()) {
            List<DeliveryCourier> available = couriers.findAvailableForDispatch(
                            freshAfter, PageRequest.of(0, properties.dispatchBatchSize()))
                    .getContent();
            if (available.isEmpty()) {
                return;
            }
            UUID bestCourier = chooseCourier(request, available);
            if (bestCourier == null) {
                continue;
            }
            try {
                assignments.assignIfEligible(request.getId(), bestCourier);
            } catch (RuntimeException ex) {
                // Do not turn an uncertain database assignment into another external effect.
                // The next sweep re-reads authoritative request/courier state.
                log.warn("Automatic delivery assignment will be retried after state reconciliation: requestId={}",
                        request.getId(), ex);
            }
        }
    }

    private UUID chooseCourier(DeliveryRequest request, List<DeliveryCourier> candidates) {
        // Shortlist by inexpensive great-circle distance, then ask the configured road router
        // for actual ETAs only for the five nearest candidates. Air distance is never used for fare.
        return candidates.stream()
                .filter(courier -> courier.getLatitude() != null && courier.getLongitude() != null)
                .sorted(Comparator.comparingDouble(courier -> greatCircleMeters(
                        courier.getLatitude(), courier.getLongitude(),
                        request.getPickupLatitude(), request.getPickupLongitude())))
                .limit(5)
                .map(courier -> routeCandidate(request, courier))
                .filter(candidate -> candidate != null)
                .min(Comparator.comparingLong(RouteCandidate::durationSeconds))
                .map(RouteCandidate::courierUserId)
                .orElse(null);
    }

    private RouteCandidate routeCandidate(DeliveryRequest request, DeliveryCourier courier) {
        try {
            DeliveryRouteMetrics route = routes.route(
                    courier.getLatitude(), courier.getLongitude(),
                    request.getPickupLatitude(), request.getPickupLongitude());
            if (route.distanceMeters() > properties.maxDistanceMeters()) {
                return null;
            }
            return new RouteCandidate(courier.getUserId(), route.durationSeconds());
        } catch (ServiceUnavailableException ex) {
            log.warn("Routing service unavailable; automatic dispatch remains queued: requestId={}",
                    request.getId());
            return null;
        }
    }

    private static double greatCircleMeters(double lat1, double lon1, double lat2, double lon2) {
        double latitudeDelta = Math.toRadians(lat2 - lat1);
        double longitudeDelta = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return 6_371_008.8 * 2 * Math.asin(Math.sqrt(Math.max(0, Math.min(1, a))));
    }

    private record RouteCandidate(UUID courierUserId, long durationSeconds) { }
}
