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
     * buyer requests it (one intent per order, the V172 partial-unique
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
}
