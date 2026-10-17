package com.marketplace.delivery;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_requests")
@Audited
public class DeliveryRequest extends BaseEntity {

    @Id
    private UUID id;

    // V224: nullable with NO inline unique — checkout-created legs carry no
    // quote (the fee froze at placement); the partial unique index
    // (V227, WHERE quote_id IS NOT NULL) keeps the #604 quoted path's
    // one-request-per-quote law. The mapping mirrors the schema exactly
    // (the review's finding: nullable=false + unique=true contradicted
    // both the factory and V224).
    @Column(name = "quote_id")
    private UUID quoteId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "requester_id", nullable = false)
    private UUID requesterId;

    @Column(name = "payment_intent_id", unique = true)
    private UUID paymentIntentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "courier_compensation_minor", nullable = false)
    private long courierCompensationMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "distance_meters", nullable = false)
    private long distanceMeters;

    @Column(name = "route_duration_seconds", nullable = false)
    private long routeDurationSeconds;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DeliveryRequestStatus status;

    @Column(name = "proof_code_hash", nullable = false, length = 64)
    private String proofCodeHash;

    @Column(name = "courier_user_id")
    private UUID courierUserId;

    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /**
     * The V229 carrier column: WHICH carrier executes this leg — the
     * platform's own fleet (the ADR-0013 D4 launch mode) or an external
     * carrier's code (arriving behind the CarrierChannel adapter
     * boundary). The platform fleet stays the default by construction.
     */
    @Column(name = "carrier", nullable = false, length = 40)
    private String carrier = "PLATFORM";

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected DeliveryRequest() { }

    public DeliveryRequest(UUID id, DeliveryQuote quote, String proofCodeHash) {
        this.id = id;
        this.quoteId = quote.getId();
        this.orderId = quote.getOrderId();
        this.requesterId = quote.getRequesterId();
        this.amountMinor = quote.getAmountMinor();
        this.courierCompensationMinor = quote.getCourierCompensationMinor();
        this.currency = quote.getCurrency();
        this.distanceMeters = quote.getDistanceMeters();
        this.routeDurationSeconds = quote.getRouteDurationSeconds();
        this.pickupAddress = quote.getPickupAddress();
        this.pickupLatitude = quote.getPickupLatitude();
        this.pickupLongitude = quote.getPickupLongitude();
        this.dropoffAddress = quote.getDropoffAddress();
        this.dropoffLatitude = quote.getDropoffLatitude();
        this.dropoffLongitude = quote.getDropoffLongitude();
        this.packageDescription = quote.getPackageDescription();
        this.proofCodeHash = proofCodeHash;
        this.status = DeliveryRequestStatus.AWAITING_PAYMENT;
    }

    /**
     * The V224 checkout-created leg (the architecture §3.2 Mode B step 4):
     * born from the order's FROZEN fulfillment selection after
     * confirmation — NO quote (the fee froze at placement, not here), NO
     * payment intent of its own (the fee already rode the order's ONE
     * intent), and READY_FOR_DISPATCH immediately (the payment already
     * happened). The courier's compensation computes from the fresh route
     * at creation (an operational fact, never the buyer's agreement).
     */
    public static DeliveryRequest fromCheckout(UUID id, UUID orderId, UUID requesterId,
                                               long amountMinor, long courierCompensationMinor,
                                               String currency, long distanceMeters,
                                               long routeDurationSeconds,
                                               String pickupAddress, double pickupLatitude,
                                               double pickupLongitude, String dropoffAddress,
                                               double dropoffLatitude, double dropoffLongitude,
                                               String packageDescription, String proofCodeHash) {
        DeliveryRequest request = new DeliveryRequest();
        request.id = id;
        request.orderId = orderId;
        request.requesterId = requesterId;
        request.amountMinor = amountMinor;
        request.courierCompensationMinor = courierCompensationMinor;
        request.currency = currency;
        request.distanceMeters = distanceMeters;
        request.routeDurationSeconds = routeDurationSeconds;
        request.pickupAddress = pickupAddress;
        request.pickupLatitude = pickupLatitude;
        request.pickupLongitude = pickupLongitude;
        request.dropoffAddress = dropoffAddress;
        request.dropoffLatitude = dropoffLatitude;
        request.dropoffLongitude = dropoffLongitude;
        request.packageDescription = packageDescription;
        request.proofCodeHash = proofCodeHash;
        request.status = DeliveryRequestStatus.READY_FOR_DISPATCH;
        return request;
    }

    @Override public UUID getId() { return id; }
    public UUID getQuoteId() { return quoteId; }
    public UUID getOrderId() { return orderId; }
    public UUID getRequesterId() { return requesterId; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public long getAmountMinor() { return amountMinor; }

    /**
     * The split edge (V224's multi-leg law): carves the new leg's share
     * out of THIS leg's amount — the frozen-fee redistribution the
     * operator's split command owns. The share must be positive and
     * leave this leg a positive remainder; the split command itself
     * enforces the stricter margin rule (the remainder never dips below
     * this leg's own courier compensation).
     */
    void carveShare(long shareMinor) {
        if (shareMinor <= 0 || shareMinor >= amountMinor) {
            throw new IllegalArgumentException(
                    "A carve requires a positive share below the leg's amount");
        }
        this.amountMinor -= shareMinor;
    }

    public long getCourierCompensationMinor() { return courierCompensationMinor; }
    public String getCurrency() { return currency; }
    public long getDistanceMeters() { return distanceMeters; }
    public long getRouteDurationSeconds() { return routeDurationSeconds; }
    public String getPickupAddress() { return pickupAddress; }
    public double getPickupLatitude() { return pickupLatitude; }
    public double getPickupLongitude() { return pickupLongitude; }
    public String getDropoffAddress() { return dropoffAddress; }
    public double getDropoffLatitude() { return dropoffLatitude; }
    public double getDropoffLongitude() { return dropoffLongitude; }
    public String getPackageDescription() { return packageDescription; }
    public DeliveryRequestStatus getStatus() { return status; }
    public UUID getCourierUserId() { return courierUserId; }
    public String getFailureReason() { return failureReason; }
    public String getCarrier() { return carrier; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public void attachPaymentIntent(UUID paymentIntentId) {
        if (this.paymentIntentId != null && !this.paymentIntentId.equals(paymentIntentId)) {
            throw new IllegalStateException("Another payment intent is already attached");
        }
        this.paymentIntentId = paymentIntentId;
    }

    public void paymentSucceeded() {
        if (status == DeliveryRequestStatus.READY_FOR_DISPATCH) return;
        require(DeliveryRequestStatus.AWAITING_PAYMENT);
        status = DeliveryRequestStatus.READY_FOR_DISPATCH;
    }

    public void paymentFailed() {
        if (status == DeliveryRequestStatus.PAYMENT_FAILED) return;
        require(DeliveryRequestStatus.AWAITING_PAYMENT);
        status = DeliveryRequestStatus.PAYMENT_FAILED;
    }

    /** Customer cancellation is permitted only before the courier collects the parcel. */
    public void cancel(String reason) {
        if (status == DeliveryRequestStatus.CANCELLED) return;
        if (status != DeliveryRequestStatus.AWAITING_PAYMENT
                && status != DeliveryRequestStatus.PAYMENT_FAILED
                && status != DeliveryRequestStatus.READY_FOR_DISPATCH
                && status != DeliveryRequestStatus.ASSIGNED) {
            throw new IllegalStateException("Delivery cannot be cancelled from " + status);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Cancellation reason is required");
        }
        status = DeliveryRequestStatus.CANCELLED;
    }

    public void assignCourier(UUID courierUserId) {
        require(DeliveryRequestStatus.READY_FOR_DISPATCH);
        if (courierUserId == null) throw new IllegalArgumentException("Courier user id is required");
        this.courierUserId = courierUserId;
        status = DeliveryRequestStatus.ASSIGNED;
    }

    public void transition(DeliveryRequestStatus target, String reason, Instant now) {
        boolean allowed = switch (status) {
            case ASSIGNED -> target == DeliveryRequestStatus.PICKED_UP || target == DeliveryRequestStatus.FAILED;
            case PICKED_UP -> target == DeliveryRequestStatus.IN_TRANSIT || target == DeliveryRequestStatus.FAILED;
            case IN_TRANSIT -> target == DeliveryRequestStatus.DELIVERED || target == DeliveryRequestStatus.FAILED;
            default -> false;
        };
        if (!allowed) {
            throw new IllegalStateException("Illegal delivery transition " + status + " -> " + target);
        }
        if (target == DeliveryRequestStatus.FAILED) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Failure reason is required");
            }
            failureReason = reason.trim();
        }
        if (target == DeliveryRequestStatus.DELIVERED) deliveredAt = now;
        status = target;
    }

    public boolean matchesProofHash(String hash) {
        return proofCodeHash != null && hash != null
                && MessageDigest.isEqual(proofCodeHash.getBytes(StandardCharsets.UTF_8),
                                         hash.getBytes(StandardCharsets.UTF_8));
    }

    private void require(DeliveryRequestStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Expected " + expected + " but was " + status);
        }
    }
}
