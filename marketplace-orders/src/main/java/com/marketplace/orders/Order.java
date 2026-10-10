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

    protected Order() {
        // JPA
    }

    private Order(UUID id, UUID consumerId, Long totalAmountMinor, String currency) {
        this.id = id;
        this.consumerId = consumerId;
        this.status = OrderStatus.PLACED;
        this.placedAt = Instant.now();
        this.totalAmountMinor = totalAmountMinor;
        this.currency = currency;
    }

    public static Order placed(UUID consumerId, Long totalAmountMinor, String currency) {
        return new Order(UUID.randomUUID(), consumerId, totalAmountMinor, currency);
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
}
