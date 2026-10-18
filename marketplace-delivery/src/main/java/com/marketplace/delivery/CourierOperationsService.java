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
 * The V229 courier management (the architecture §4.2: «Courier/driver
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

    public CourierOperationsService(DeliveryCourierRepository couriers) {
        this.couriers = couriers;
    }

    /** The roster — the couriers board with availability, status, and the current job. */
    @Transactional(readOnly = true)
    public Page<CourierStatusResponse> roster(Pageable pageable) {
        return couriers.findAllByOrderByUpdatedAtDesc(pageable).map(CourierStatusResponse::of);
    }

    /**
     * The operator's suspension — new work stops; the in-flight leg (if any) finishes.
     * The verdict's reason rides the courier's own row (bounded 500) and the Envers
     * revision history carries the who/when — CodeRabbit round 1, verified real:
     * delivery_request_events.delivery_request_id is NOT NULL by V215's design, so
     * an idle courier's suspension must never write there (the insert would violate
     * the constraint and roll back the whole status change).
     */
    @Transactional
    @Observed(name = "delivery.courier.suspend")
    public CourierStatusResponse suspend(UUID courierUserId, String reason) {
        DeliveryCourier courier = couriers.findByUserIdForUpdate(courierUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Courier", courierUserId));
        if (courier.getStatus() == CourierStatus.SUSPENDED) {
            throw new ConflictException("Courier " + courierUserId + " is already suspended");
        }
        courier.suspend(reason);
        DeliveryCourier saved = couriers.save(courier);
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
                                        java.time.Instant locationUpdatedAt,
                                        String statusReason) {

        static CourierStatusResponse of(DeliveryCourier courier) {
            return new CourierStatusResponse(courier.getUserId(), courier.getStatus(),
                    courier.isAvailable(), courier.getCurrentDeliveryRequestId(),
                    courier.getLatitude(), courier.getLongitude(),
                    courier.getLocationUpdatedAt(), courier.getStatusReason());
        }
    }
}
