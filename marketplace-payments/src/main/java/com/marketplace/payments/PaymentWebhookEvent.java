package com.marketplace.payments;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "payment_webhook_events")
public class PaymentWebhookEvent extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "event_id", nullable = false, unique = true, length = 200)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    protected PaymentWebhookEvent() {}

    private PaymentWebhookEvent(UUID id, String provider, String eventId, String eventType) {
        this.id = id;
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType;
    }

    public static PaymentWebhookEvent create(String provider, String eventId, String eventType) {
        return new PaymentWebhookEvent(UUID.randomUUID(), provider, eventId, eventType);
    }

    @Override
    public UUID getId() { return id; }
}
