package com.marketplace.payments;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "external_id", length = 200)
    private String externalId;

    protected Payment() {
    }

    public Payment(UUID id, UUID paymentIntentId, Long amountCents) {
        this.id = id;
        this.paymentIntentId = paymentIntentId;
        this.amountCents = amountCents;
    }

    public static Payment create(UUID paymentIntentId, Long amountCents) {
        return new Payment(UUID.randomUUID(), paymentIntentId, amountCents);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public Long getAmountCents() { return amountCents; }
    public PaymentStatus getStatus() { return status; }
    public String getExternalId() { return externalId; }

    public void markCompleted(String externalId) {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Can only complete PENDING payments");
        }
        this.status = PaymentStatus.COMPLETED;
        this.externalId = externalId;
    }

    public void markFailed() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Can only fail PENDING payments");
        }
        this.status = PaymentStatus.FAILED;
    }

    public void markRefunded() {
        if (this.status != PaymentStatus.COMPLETED) {
            throw new IllegalStateException("Can only refund COMPLETED payments");
        }
        this.status = PaymentStatus.REFUNDED;
    }
}