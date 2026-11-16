package com.marketplace.orders;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * The V225 RMA lifecycle (the Saleor fit-gap study §7-Phase-3 item 4):
 * one return or exchange request against a FULFILLED order's goods —
 * modeled SEPARATELY from payment refunds (a refund is a money movement;
 * THIS is goods coming back). The lifecycle:
 *
 * <pre>
 * REQUESTED → APPROVED → RECEIVED → COMPLETED
 *     │           │
 *     └───────────┴──→ REJECTED / CANCELLED (terminal)
 * </pre>
 *
 * <p><b>The frozen refund share:</b> computed and FROZEN at APPROVAL from
 * the order's own frozen breakdown — the lines' share of the discounted
 * goods value ({@code Σ(unit × qty) × (subtotal − discount) / subtotal}),
 * plus the exclusive-tax share when the jurisdiction adds tax on top
 * (INCLUSIVE tax is already inside the goods value). The completion
 * executes exactly this number through the ONE refund contract — never a
 * recomputation (the agreement froze at placement; the return freezes its
 * consequence at approval).
 *
 * <p><b>The exchange shape:</b> an EXCHANGE completes by creating the
 * REPLACEMENT order (same seller, same lines, zero payment — the buyer
 * already paid) linked through {@code replacementOrderId}; the replacement
 * rides the ordinary machine (confirm → deliver/fulfill).
 */
@Entity
@Table(name = "order_returns")
@Audited
public class OrderReturn extends BaseEntity {

    public enum ReturnType { RETURN, EXCHANGE }

    public enum ReturnStatus { REQUESTED, APPROVED, REJECTED, RECEIVED, COMPLETED, CANCELLED }

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_type", nullable = false, length = 10)
    private ReturnType returnType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ReturnStatus status;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "refund_amount_minor")
    private Long refundAmountMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "replacement_order_id")
    private UUID replacementOrderId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected OrderReturn() {
    }

    private OrderReturn(UUID id, UUID orderId, UUID consumerId, ReturnType type,
                        String reason, String currency, Instant at) {
        this.id = id;
        this.orderId = orderId;
        this.consumerId = consumerId;
        this.returnType = type;
        this.status = ReturnStatus.REQUESTED;
        this.reason = reason;
        this.currency = currency;
        this.requestedAt = at;
    }

    public static OrderReturn requested(UUID orderId, UUID consumerId, ReturnType type,
                                        String reason, String currency, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A return requires a reason");
        }
        return new OrderReturn(UUID.randomUUID(), orderId, consumerId, type,
                reason.strip(), currency, at);
    }

    public void approve(long refundAmountMinor, Instant at) {
        require(ReturnStatus.REQUESTED);
        // CodeRabbit agent findings (2026-10-26T11:43 export), row 1: a
        // ZERO share is the honest freeze for a fully-discounted goods
        // basket with no applicable tax — the goods gave back nothing, so
        // the completion refunds nothing (it must never mint a minor unit
        // out of the collected shipping charge). Only a NEGATIVE share is
        // nonsense and refuses. The shape constraint lives in
        // V287__order_returns_zero_value_refund.sql (>= 0).
        if (refundAmountMinor < 0) {
            throw new IllegalStateException("A return's frozen refund share is never negative");
        }
        this.refundAmountMinor = refundAmountMinor;
        this.approvedAt = at;
        this.status = ReturnStatus.APPROVED;
    }

    public void reject(String note, Instant at) {
        require(ReturnStatus.REQUESTED);
        this.resolutionNote = note == null ? null : note.strip();
        this.status = ReturnStatus.REJECTED;
    }

    public void receive(Instant at) {
        require(ReturnStatus.APPROVED);
        this.receivedAt = at;
        this.status = ReturnStatus.RECEIVED;
    }

    public void complete(UUID replacementOrderId, Instant at) {
        require(ReturnStatus.RECEIVED);
        if (returnType == ReturnType.EXCHANGE && replacementOrderId == null) {
            throw new IllegalStateException("An exchange completes with its replacement order");
        }
        this.replacementOrderId = replacementOrderId;
        this.completedAt = at;
        this.status = ReturnStatus.COMPLETED;
    }

    public void cancel(String note, Instant at) {
        require(ReturnStatus.REQUESTED);
        this.resolutionNote = note == null ? null : note.strip();
        this.status = ReturnStatus.CANCELLED;
    }

    private void require(ReturnStatus expected) {
        if (this.status != expected) {
            throw new IllegalStateException("Return is " + status
                    + " — the transition is legal from " + expected + " only");
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getConsumerId() {
        return consumerId;
    }

    public ReturnType getReturnType() {
        return returnType;
    }

    public ReturnStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public Long getRefundAmountMinor() {
        return refundAmountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getReplacementOrderId() {
        return replacementOrderId;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
