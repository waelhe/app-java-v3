package com.marketplace.orders;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * A-11 (compliance plan wave C: C.1) — the order: the cart's immutable
 * snapshot promoted to the fulfillment machine. Placement copies every cart
 * line into an {@link OrderItem} (amounts and quantities frozen), totals the
 * order, and tombstones the cart — all in the ONE placement transaction, so
 * the order and its lines are either both durable or both absent (the
 * snapshot never half-exists).
 *
 * <p>Transition timestamps ({@code confirmedAt}/{@code fulfilledAt}/
 * {@code cancelledAt}) are set by the service at the same instant the state
 * flips — the audit trail is both the Envers revision history and the
 * machine's own clock record.
 */
@Entity
@Table(name = "orders")
@Audited
public class Order extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "total_amount_minor", nullable = false)
    private Long totalAmountMinor;

    /**
     * The Saleor fit-gap study's Phase 3 (PR #602 §7-Phase-3): the order's
     * frozen SUBTOTAL — the sum of the re-priced lines BEFORE the commercial
     * rules (promotions, vouchers, taxes). Nullable for the pre-V223 orders:
     * their total is the whole recorded agreement exactly as frozen at THEIR
     * placement (the snapshot is never back-filled — history is not edited).
     */
    @Column(name = "subtotal_amount_minor")
    private Long subtotalAmountMinor;

    /** The frozen promotion + voucher discount (the components' sum). */
    @Column(name = "discount_amount_minor")
    private Long discountAmountMinor;

    /**
     * The frozen tax component. INCLUSIVE jurisdictions: the component
     * contained INSIDE the agreed amounts (the total already carries it);
     * EXCLUSIVE: the component ADDED on top. Either way the order records
     * the explicit tax fact at placement (the study's §5 rule).
     */
    @Column(name = "tax_amount_minor")
    private Long taxAmountMinor;

    /**
     * The V224 checkout fulfillment selection (the architecture §3.2
     * Mode B): PICKUP, PLATFORM_DELIVERY, or NULL (the pre-V224 legacy
     * agreement — its total stays the whole recorded agreement).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_method", length = 16)
    private FulfillmentMethod fulfillmentMethod;

    /**
     * The frozen platform-delivery fee — present exactly when the method
     * is PLATFORM_DELIVERY, and ALREADY INSIDE {@code totalAmountMinor}
     * (the order's ONE payment intent carries it; no second charge).
     */
    @Column(name = "shipping_amount_minor")
    private Long shippingAmountMinor;

    /** The destination snapshot — the address the platform delivers to. */
    @Column(name = "ship_to_address", length = 250)
    private String shipToAddress;

    @Column(name = "ship_to_latitude")
    private Double shipToLatitude;

    @Column(name = "ship_to_longitude")
    private Double shipToLongitude;

    @NotBlank
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * Stage 6 (ADR-0002): the order's single seller — stored at placement
     * (the single-seller cart invariant; mixed-seller carts answer the
     * house 409). A plain user id, never a JPA relation (the V32
     * discipline); nullable because legacy orders predate the invariant.
     */
    @Column(name = "seller_id")
    private UUID sellerId;

    /**
     * Stage 6 (ADR-0002): the order's payment intent — linked when the
     * buyer requests it (one intent per order, the V184 partial-unique
     * index); the settlement's COMPLETED event auto-confirms from here.
     */
    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    /**
     * Stage 6 (ADR-0002): whether placement reserved the lines' stock —
     * the flag the cancel/fulfill edges read to release/commit exactly
     * once (the machine's guard is the idempotency).
     */
    @Column(name = "stock_reserved", nullable = false)
    private boolean stockReserved;

    /** Delivery handoff is owned by marketplace-delivery through a shared port. */
    @Column(name = "delivery_request_id", unique = true)
    private UUID deliveryRequestId;

    protected Order() {
        // JPA
    }

    private Order(UUID id, UUID consumerId, Long totalAmountMinor, String currency, UUID sellerId) {
        this.id = id;
        this.consumerId = consumerId;
        this.status = OrderStatus.PLACED;
        this.placedAt = Instant.now();
        this.totalAmountMinor = totalAmountMinor;
        this.currency = currency;
        this.sellerId = sellerId;
    }

    public static Order placed(UUID consumerId, Long totalAmountMinor, String currency, UUID sellerId) {
        return new Order(UUID.randomUUID(), consumerId, totalAmountMinor, currency, sellerId);
    }

    /**
     * The Phase-3 placement factory: the order carries its COMMERCIAL
     * BREAKDOWN — subtotal (the re-priced lines), discount (promotion +
     * voucher, itemized in {@code order_price_components}), tax (the
     * jurisdiction's explicit policy), and the TOTAL (the buyer's
     * agreement: subtotal − discount + tax when EXCLUSIVE, the discounted
     * subtotal when INCLUSIVE). One placement, one frozen breakdown.
     */
    public static Order placedWithBreakdown(UUID consumerId, String currency, UUID sellerId,
                                            long subtotalMinor, long discountMinor,
                                            long taxMinor, boolean taxInclusive) {
        long total = taxInclusive ? subtotalMinor - discountMinor
                : subtotalMinor - discountMinor + taxMinor;
        Order order = new Order(UUID.randomUUID(), consumerId, total, currency, sellerId);
        order.subtotalAmountMinor = subtotalMinor;
        order.discountAmountMinor = discountMinor;
        order.taxAmountMinor = taxMinor;
        return order;
    }

    /**
     * The V224 checkout placement factory — the commercial breakdown PLUS
     * the frozen fulfillment selection: the shipping fee (platform
     * delivery) joins the total; the destination snapshot freezes beside
     * it. PICKUP records the method with no destination and no fee (the
     * V224 shape law).
     */
    public static Order placedWithFulfillment(UUID consumerId, String currency, UUID sellerId,
                                              long subtotalMinor, long discountMinor,
                                              long taxMinor, boolean taxInclusive,
                                              FulfillmentMethod method,
                                              String shipToAddress,
                                              Double shipToLatitude, Double shipToLongitude,
                                              long shippingMinor) {
        Order order = placedWithBreakdown(consumerId, currency, sellerId, subtotalMinor,
                discountMinor, taxMinor, taxInclusive);
        order.fulfillmentMethod = method;
        if (method == FulfillmentMethod.PLATFORM_DELIVERY) {
            if (shipToAddress == null || shipToAddress.isBlank()
                    || shipToLatitude == null || shipToLongitude == null || shippingMinor <= 0) {
                throw new IllegalArgumentException(
                        "A platform-delivery selection requires a destination and a positive fee");
            }
            order.shipToAddress = shipToAddress.strip();
            order.shipToLatitude = shipToLatitude;
            order.shipToLongitude = shipToLongitude;
            order.shippingAmountMinor = shippingMinor;
            order.totalAmountMinor += shippingMinor;
        } else {
            order.shippingAmountMinor = 0L;
        }
        return order;
    }

    public void confirm(Instant at) {
        this.status = OrderStatus.CONFIRMED;
        this.confirmedAt = at;
    }

    public void fulfill(Instant at) {
        this.status = OrderStatus.FULFILLED;
        this.fulfilledAt = at;
    }

    public void cancel(String reason, Instant at) {
        this.status = OrderStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = at;
    }

    public void linkPaymentIntent(UUID paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
    }

    public void markStockReserved() {
        this.stockReserved = true;
    }

    public void markStockReleased() {
        this.stockReserved = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getConsumerId() {
        return consumerId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public Long getTotalAmountMinor() {
        return totalAmountMinor;
    }

    public Long getSubtotalAmountMinor() {
        return subtotalAmountMinor;
    }

    public Long getDiscountAmountMinor() {
        return discountAmountMinor;
    }

    public Long getTaxAmountMinor() {
        return taxAmountMinor;
    }

    public FulfillmentMethod getFulfillmentMethod() {
        return fulfillmentMethod;
    }

    public Long getShippingAmountMinor() {
        return shippingAmountMinor;
    }

    public String getShipToAddress() {
        return shipToAddress;
    }

    public Double getShipToLatitude() {
        return shipToLatitude;
    }

    public Double getShipToLongitude() {
        return shipToLongitude;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public UUID getPaymentIntentId() {
        return paymentIntentId;
    }

    public boolean isStockReserved() {
        return stockReserved;
    }

    public UUID getDeliveryRequestId() {
        return deliveryRequestId;
    }

    public void attachDeliveryRequest(UUID requestId) {
        if (requestId == null) throw new IllegalArgumentException("Delivery request id is required");
        if (deliveryRequestId != null && !deliveryRequestId.equals(requestId)) {
            throw new IllegalStateException("Order already has a delivery request");
        }
        deliveryRequestId = requestId;
    }

    public void clearDeliveryRequest(UUID requestId) {
        if (requestId != null && requestId.equals(deliveryRequestId)) {
            deliveryRequestId = null;
        }
    }


}
