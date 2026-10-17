package com.marketplace.delivery;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    public void updateAvailability(double latitude, double longitude, boolean available, Instant now) {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Courier coordinates are invalid");
        }
        if (currentDeliveryRequestId != null && available) {
            throw new IllegalStateException("Courier cannot accept work while assigned to an active delivery");
        }
        this.latitude = latitude;
        this.longitude = longitude;
        this.locationUpdatedAt = now;
        this.available = available;
    }

    public void assign(UUID requestId, Instant now) {
        if (!available || currentDeliveryRequestId != null || latitude == null
                || longitude == null || locationUpdatedAt == null) {
            throw new IllegalStateException("Courier is not available for assignment");
        }
        currentDeliveryRequestId = requestId;
        lastAssignedAt = now;
        available = false;
    }

    public void finish(UUID requestId, boolean returnToAvailable, Instant now, long maxLocationAgeSeconds) {
        if (!requestId.equals(currentDeliveryRequestId)) {
            throw new IllegalStateException("Delivery is not assigned to this courier");
        }
        currentDeliveryRequestId = null;
        boolean freshLocation = locationUpdatedAt != null
                && locationUpdatedAt.plusSeconds(maxLocationAgeSeconds).isAfter(now);
        available = returnToAvailable && freshLocation && latitude != null && longitude != null;
    }
}
