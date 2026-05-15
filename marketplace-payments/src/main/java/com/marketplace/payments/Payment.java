package com.marketplace.payments;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

@Entity
@Table(name = "payments")
@Audited
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
        this.status.validateTransitionTo(PaymentStatus.COMPLETED);
        this.status = PaymentStatus.COMPLETED;
        this.externalId = externalId;
    }

    public void markFailed() {
        this.status.validateTransitionTo(PaymentStatus.FAILED);
        this.status = PaymentStatus.FAILED;
    }

    public void markRefunded() {
        this.status.validateTransitionTo(PaymentStatus.REFUNDED);
        this.status = PaymentStatus.REFUNDED;
    }
}