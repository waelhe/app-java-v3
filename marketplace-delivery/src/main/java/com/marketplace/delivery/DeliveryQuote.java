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
@Table(name = "delivery_quotes")
@Audited
public class DeliveryQuote extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "pickup_address", nullable = false, length = 250)
    private String pickupAddress;
    @Column(name = "pickup_latitude", nullable = false)
    private double pickupLatitude;
    @Column(name = "pickup_longitude", nullable = false)
    private double pickupLongitude;

    @Column(name = "dropoff_address", nullable = false, length = 250)
    private String dropoffAddress;
    @Column(name = "dropoff_latitude", nullable = false)
    private double dropoffLatitude;
    @Column(name = "dropoff_longitude", nullable = false)
    private double dropoffLongitude;

    @Column(name = "package_description", nullable = false, length = 500)
    private String packageDescription;
    @Column(name = "distance_meters", nullable = false)
    private long distanceMeters;
    @Column(name = "route_duration_seconds", nullable = false)
    private long routeDurationSeconds;
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;
    @Column(name = "courier_compensation_minor", nullable = false)
    private long courierCompensationMinor;
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "order_id")
    private UUID orderId;

    protected DeliveryQuote() { }

    public DeliveryQuote(UUID id, UUID requesterId, String pickupAddress, double pickupLatitude,
                         double pickupLongitude, String dropoffAddress, double dropoffLatitude,
                         double dropoffLongitude, String packageDescription, long distanceMeters,
                         long routeDurationSeconds, long amountMinor, long courierCompensationMinor,
                         String currency, Instant expiresAt) {
        this(id, requesterId, pickupAddress, pickupLatitude, pickupLongitude, dropoffAddress,
                dropoffLatitude, dropoffLongitude, packageDescription, distanceMeters, routeDurationSeconds,
                amountMinor, courierCompensationMinor, currency, expiresAt, null);
    }

    public DeliveryQuote(UUID id, UUID requesterId, String pickupAddress, double pickupLatitude,
                         double pickupLongitude, String dropoffAddress, double dropoffLatitude,
                         double dropoffLongitude, String packageDescription, long distanceMeters,
                         long routeDurationSeconds, long amountMinor, long courierCompensationMinor,
                         String currency, Instant expiresAt, UUID orderId) {
        this.id = id;
        this.requesterId = requesterId;
        this.pickupAddress = pickupAddress;
        this.pickupLatitude = pickupLatitude;
        this.pickupLongitude = pickupLongitude;
        this.dropoffAddress = dropoffAddress;
        this.dropoffLatitude = dropoffLatitude;
        this.dropoffLongitude = dropoffLongitude;
        this.packageDescription = packageDescription;
        this.distanceMeters = distanceMeters;
        this.routeDurationSeconds = routeDurationSeconds;
        this.amountMinor = amountMinor;
        this.courierCompensationMinor = courierCompensationMinor;
        this.currency = currency;
        this.expiresAt = expiresAt;
        this.orderId = orderId;
    }

    /** Compatibility constructor for existing unit tests and non-routing fixtures. */
    public DeliveryQuote(UUID id, UUID requesterId, String pickupAddress, double pickupLatitude,
                         double pickupLongitude, String dropoffAddress, double dropoffLatitude,
                         double dropoffLongitude, String packageDescription, long distanceMeters,
                         long amountMinor, String currency, Instant expiresAt) {
        this(id, requesterId, pickupAddress, pickupLatitude, pickupLongitude, dropoffAddress,
                dropoffLatitude, dropoffLongitude, packageDescription, distanceMeters, 1,
                amountMinor, 0, currency, expiresAt);
    }

    @Override public UUID getId() { return id; }
    public UUID getRequesterId() { return requesterId; }
    public String getPickupAddress() { return pickupAddress; }
    public double getPickupLatitude() { return pickupLatitude; }
    public double getPickupLongitude() { return pickupLongitude; }
    public String getDropoffAddress() { return dropoffAddress; }
    public double getDropoffLatitude() { return dropoffLatitude; }
    public double getDropoffLongitude() { return dropoffLongitude; }
    public String getPackageDescription() { return packageDescription; }
    public long getDistanceMeters() { return distanceMeters; }
    public long getRouteDurationSeconds() { return routeDurationSeconds; }
    public long getAmountMinor() { return amountMinor; }
    public long getCourierCompensationMinor() { return courierCompensationMinor; }
    public String getCurrency() { return currency; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public UUID getOrderId() { return orderId; }

    public void consume(Instant now) {
        if (consumedAt != null || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("Quote already consumed or expired");
        }
        consumedAt = now;
    }
}
