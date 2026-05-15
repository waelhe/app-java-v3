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
@Table(name = "payment_intents")
@Audited
public class PaymentIntent extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "SAR";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentIntentStatus status = PaymentIntentStatus.CREATED;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    protected PaymentIntent() {
    }

    public PaymentIntent(UUID id, UUID bookingId, UUID consumerId,
                         Long amountCents, String idempotencyKey) {
        this.id = id;
        this.bookingId = bookingId;
        this.consumerId = consumerId;
        this.amountCents = amountCents;
        this.idempotencyKey = idempotencyKey;
    }

    public static PaymentIntent create(UUID bookingId, UUID consumerId,
                                        Long amountCents, String idempotencyKey) {
        return new PaymentIntent(UUID.randomUUID(), bookingId, consumerId, amountCents, idempotencyKey);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getConsumerId() { return consumerId; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public PaymentIntentStatus getStatus() { return status; }
    public String getIdempotencyKey() { return idempotencyKey; }

    public void markProcessing() {
        this.status.validateTransitionTo(PaymentIntentStatus.PROCESSING);
        this.status = PaymentIntentStatus.PROCESSING;
    }

    public void markSucceeded() {
        this.status.validateTransitionTo(PaymentIntentStatus.SUCCEEDED);
        this.status = PaymentIntentStatus.SUCCEEDED;
    }

    public void markFailed() {
        this.status.validateTransitionTo(PaymentIntentStatus.FAILED);
        this.status = PaymentIntentStatus.FAILED;
    }

    public void cancel() {
        this.status.validateTransitionTo(PaymentIntentStatus.CANCELLED);
        this.status = PaymentIntentStatus.CANCELLED;
    }
}