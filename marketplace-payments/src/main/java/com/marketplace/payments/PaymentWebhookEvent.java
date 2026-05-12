package com.marketplace.payments;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent extends BaseEntity {
    @Id private UUID id;
    @Column(name = "provider", nullable = false, length = 40) private String provider;
    @Column(name = "external_event_id", nullable = false, length = 120) private String externalEventId;
    @Column(name = "payment_intent_id", nullable = false) private UUID paymentIntentId;
    @Column(name = "event_type", nullable = false, length = 80) private String eventType;
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT") private String payload;
    protected PaymentWebhookEvent() {}
    public PaymentWebhookEvent(UUID id, String provider, String externalEventId, UUID paymentIntentId, String eventType, String payload) {
        this.id = id; this.provider = provider; this.externalEventId = externalEventId; this.paymentIntentId = paymentIntentId; this.eventType = eventType; this.payload = payload;
    }
    public static PaymentWebhookEvent create(String provider, String externalEventId, UUID paymentIntentId, String eventType, String payload) {
        return new PaymentWebhookEvent(UUID.randomUUID(), provider, externalEventId, paymentIntentId, eventType, payload);
    }
    @Override public UUID getId() { return id; }
}
