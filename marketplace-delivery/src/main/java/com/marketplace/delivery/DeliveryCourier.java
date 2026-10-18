package com.marketplace.delivery;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_couriers")
@Audited
public class DeliveryCourier extends BaseEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "available", nullable = false)
    private boolean available;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "location_updated_at")
    private Instant locationUpdatedAt;

    @Column(name = "current_delivery_request_id", unique = true)
    private UUID currentDeliveryRequestId;

    @Column(name = "last_assigned_at")
    private Instant lastAssignedAt;

    /**
     * The V229 OPERATIONAL lifecycle (the dispatcher's verdict, not the
     * courier's own live signal): a SUSPENDED courier disappears from
     * dispatch eligibility — {@link #assign} refuses, whatever the
     * availability flag says. Suspending a courier mid-job does not tear
     * the active delivery away (the leg finishes; new work stops).
     */
    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private CourierStatus status = CourierStatus.ACTIVE;

    /**
     * The operator's verdict's own words (CodeRabbit round 1, verified
     * real): the suspension/reactivation reason lives on the courier's own
     * row — NOT on delivery_request_events, whose delivery_request_id is
     * NOT NULL by V215's design and would roll back an idle courier's
     * suspension. The Envers _aud revision history carries the who/when of
     * every change — the order_returns.reason/resolution_note pattern.
     */
    @Column(name = "status_reason", length = 500)
    private String statusReason;

    protected DeliveryCourier() { }

    public DeliveryCourier(UUID userId) {
        this.userId = java.util.Objects.requireNonNull(userId);
    }

    @Override
    public UUID getId() {
        return userId;
    }

    public UUID getUserId() { return userId; }
    public boolean isAvailable() { return available; }
    public Double getLatitude() { return latitude; }
    public Double getLongitude() { return longitude; }
    public Instant getLocationUpdatedAt() { return locationUpdatedAt; }
    public UUID getCurrentDeliveryRequestId() { return currentDeliveryRequestId; }
    public Instant getLastAssignedAt() { return lastAssignedAt; }
    public CourierStatus getStatus() { return status; }
    public String getStatusReason() { return statusReason; }

    public void updateAvailability(double latitude, double longitude, boolean available, Instant now) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Courier coordinates are invalid");
        }
        if (currentDeliveryRequestId != null && available) {
            throw new IllegalStateException("Courier cannot accept work while assigned to an active delivery");
        }
        if (status == CourierStatus.SUSPENDED && available) {
            throw new IllegalStateException("A suspended courier cannot declare availability");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationUpdatedAt = now;
        this.available = available;
    }

    public void assign(UUID requestId, Instant now) {
        if (status == CourierStatus.SUSPENDED) {
            throw new IllegalStateException("A suspended courier cannot take new work");
        }
        if (!available || currentDeliveryRequestId != null || latitude == null
                || longitude == null || locationUpdatedAt == null) {
            throw new IllegalStateException("Courier is not available for assignment");
        }
        currentDeliveryRequestId = requestId;
        lastAssignedAt = now;
        available = false;
    }

    /**
     * The operator's suspension — the active leg (if any) finishes; new
     * work stops. The reason rides the row (bounded to the column's 500);
     * reactivation clears it.
     */
    public void suspend(String reason) {
        this.status = CourierStatus.SUSPENDED;
        this.available = false;
        this.statusReason = reason == null ? null
                : reason.strip().isEmpty() ? null : reason.strip();
    }

    /**
     * The operator's reactivation — the courier re-declares availability to
     * re-enter dispatch (CodeRabbit round 1, verified real): re-entry NEVER
     * auto-restores availability — the courier's own live signal must be
     * re-declared through {@link #updateAvailability}; the verdict's reason
     * is cleared with the verdict itself.
     */
    public void reactivate() {
        this.status = CourierStatus.ACTIVE;
        this.available = false;
        this.statusReason = null;
    }

    public void finish(UUID requestId, boolean returnToAvailable, Instant now, long maxLocationAgeSeconds) {
        if (!requestId.equals(currentDeliveryRequestId)) {
            throw new IllegalStateException("Delivery is not assigned to this courier");
        }
        currentDeliveryRequestId = null;
        boolean freshLocation = locationUpdatedAt != null
                && locationUpdatedAt.plusSeconds(maxLocationAgeSeconds).isAfter(now);
        // CodeRabbit round 1, verified real: a leg finishing under a
        // SUSPENDED courier must NOT restore availability — the courier
        // re-declares it after (and if) the operator reactivates them;
        // available=true requires status=ACTIVE, the same invariant
        // updateAvailability and assign already enforce.
        available = returnToAvailable && freshLocation && latitude != null && longitude != null
                && status != CourierStatus.SUSPENDED;
    }
}
