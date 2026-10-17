package com.marketplace.delivery;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ProviderLookupPort;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class DeliveryAssignmentService {

    private final DeliveryRequestRepository requests;
    private final DeliveryCourierRepository couriers;
    private final DeliveryRequestEventRepository events;
    private final DeliveryProperties properties;
    private final ProviderLookupPort providers;

    public DeliveryAssignmentService(DeliveryRequestRepository requests,
                                     DeliveryCourierRepository couriers,
                                     DeliveryRequestEventRepository events,
                                     DeliveryProperties properties,
                                     ProviderLookupPort providers) {
        this.requests = requests;
        this.couriers = couriers;
        this.events = events;
        this.properties = properties;
        this.providers = providers;
    }

    @Observed(name = "delivery.dispatch.claim")
    @Transactional
    public boolean assignIfEligible(UUID requestId, UUID courierUserId) {
        Instant now = Instant.now();
        DeliveryRequest request = requests.findByIdForUpdate(requestId).orElse(null);
        if (request == null || request.getStatus() != DeliveryRequestStatus.READY_FOR_DISPATCH
                || request.getCourierUserId() != null) {
            return false;
        }
        if (providers.findByUserId(courierUserId).filter(p -> "VERIFIED".equals(p.status())).isEmpty()) {
            return false;
        }
        DeliveryCourier courier = couriers.findByUserIdForUpdate(courierUserId).orElse(null);
        if (courier == null || !courier.isAvailable() || courier.getCurrentDeliveryRequestId() != null
                || courier.getLocationUpdatedAt() == null
                || !courier.getLocationUpdatedAt().plusSeconds(properties.courierLocationMaxAgeSeconds()).isAfter(now)) {
            return false;
        }
        try {
            request.assignCourier(courierUserId);
            courier.assign(requestId, now);
        } catch (IllegalStateException ex) {
            throw new ConflictException(ex.getMessage());
        }
        requests.save(request);
        couriers.save(courier);
        events.save(new DeliveryRequestEvent(UUID.randomUUID(), requestId, courierUserId,
                DeliveryRequestStatus.READY_FOR_DISPATCH.name(), DeliveryRequestStatus.ASSIGNED.name(),
                "Automatically assigned to eligible courier", now));
        return true;
    }
}
