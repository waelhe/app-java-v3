package com.marketplace.delivery;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "delivery_request_events")
@Audited
public class DeliveryRequestEvent extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "delivery_request_id", nullable = false)
    private UUID deliveryRequestId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32)
    private String toStatus;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected DeliveryRequestEvent() {
    }

    public DeliveryRequestEvent(UUID id, UUID request, UUID actor, String from, String to, String reason, Instant at) {
        this.id = id;
        deliveryRequestId = request;
        actorId = actor;
        fromStatus = from;
        toStatus = to;
        this.reason = reason;
        occurredAt = at;
    }

    @Override
    public UUID getId() {
        return id;
    }
}
