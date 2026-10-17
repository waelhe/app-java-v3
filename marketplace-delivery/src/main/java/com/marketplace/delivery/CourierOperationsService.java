package com.marketplace.delivery;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The V226 courier management (the architecture §4.2: «Courier/driver
 * onboarding and scoped role, assignment, availability, incident
 * reporting and job history» — this slice lands the OPERATIONAL
 * lifecycle): the roster read, the operator's suspension, and the
 * reactivation. A suspension is the operator's standing verdict — the
 * courier disappears from dispatch eligibility instantly (the assignment
 * gate reads the status), an in-flight leg finishes untouched (tearing it
 * away would orphan a physical delivery mid-route), and the courier's own
 * availability flag stays the LIVE signal it always was.
 */
@Service
public class CourierOperationsService {

    private final DeliveryCourierRepository couriers;
    private final DeliveryRequestEventRepository events;

    public CourierOperationsService(DeliveryCourierRepository couriers,
                                    DeliveryRequestEventRepository events) {
        this.couriers = couriers;
        this.events = events;
    }

    /** The roster — the couriers board with availability, status, and the current job. */
    @Transactional(readOnly = true)
    public Page<CourierStatusResponse> roster(Pageable pageable) {
        return couriers.findAllByOrderByUpdatedAtDesc(pageable).map(CourierStatusResponse::of);
    }

    /** The operator's suspension — new work stops; the in-flight leg (if any) finishes. */
    @Transactional
    @Observed(name = "delivery.courier.suspend")
    public CourierStatusResponse suspend(UUID courierUserId, String reason) {
        DeliveryCourier courier = couriers.findByUserIdForUpdate(courierUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Courier", courierUserId));
        if (courier.getStatus() == CourierStatus.SUSPENDED) {
            throw new ConflictException("Courier " + courierUserId + " is already suspended");
        }
        courier.suspend();
        DeliveryCourier saved = couriers.save(courier);
        events.save(new DeliveryRequestEvent(UUID.randomUUID(),
                courier.getCurrentDeliveryRequestId(), null,
                "ACTIVE", "SUSPENDED",
                "Courier suspended by the operator: " + (reason == null ? "" : reason.strip()),
                java.time.Instant.now()));
        return CourierStatusResponse.of(saved);
    }

    /** The operator's reactivation — the courier re-declares availability to re-enter dispatch. */
    @Transactional
    @Observed(name = "delivery.courier.reactivate")
    public CourierStatusResponse reactivate(UUID courierUserId) {
        DeliveryCourier courier = couriers.findByUserIdForUpdate(courierUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Courier", courierUserId));
        if (courier.getStatus() == CourierStatus.ACTIVE) {
            throw new ConflictException("Courier " + courierUserId + " is already active");
        }
        courier.reactivate();
        return CourierStatusResponse.of(couriers.save(courier));
    }

    /** The roster's read shape (the wire record — no entity locals at the boundary). */
    public record CourierStatusResponse(UUID userId, CourierStatus status, boolean available,
                                        UUID currentDeliveryRequestId,
                                        Double latitude, Double longitude,
                                        java.time.Instant locationUpdatedAt) {

        static CourierStatusResponse of(DeliveryCourier courier) {
            return new CourierStatusResponse(courier.getUserId(), courier.getStatus(),
                    courier.isAvailable(), courier.getCurrentDeliveryRequestId(),
                    courier.getLatitude(), courier.getLongitude(),
                    courier.getLocationUpdatedAt());
        }
    }
}
