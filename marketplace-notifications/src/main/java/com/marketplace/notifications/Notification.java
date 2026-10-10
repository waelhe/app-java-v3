package com.marketplace.notifications;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.util.UUID;

@Entity
@Table(name = "notifications")
@Audited
public class Notification extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "type", nullable = false, length = 100)
    private String type;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    /**
     * Task 5-f (the delivery-dedup ledger): the originating event's id once
     * the delivery rides a re-deliverable Modulith publication — nullable by
     * design (the fifteen pre-ledger delivery paths carry no source event),
     * guarded DB-side by the partial unique index
     * {@code uq_notifications_source_event_once} on
     * {@code (recipient_id, source_event_id)} (the V93
     * provider_follow_alerts ledger precedent: one row per (recipient,
     * source event) EVER delivered; re-delivery can never duplicate).
     */
    @Column(name = "source_event_id")
    private UUID sourceEventId;

    protected Notification() {}

    private Notification(UUID id, UUID recipientId, String type, String message, boolean read) {
        this(id, recipientId, type, message, read, null);
    }

    private Notification(UUID id, UUID recipientId, String type, String message, boolean read,
                         UUID sourceEventId) {
        this.id = id;
        this.recipientId = recipientId;
        this.type = type;
        this.message = message;
        this.read = read;
        this.sourceEventId = sourceEventId;
    }

    public static Notification create(UUID recipientId, String type, String message) {
        return new Notification(UUID.randomUUID(), recipientId, type, message, false);
    }

    /**
     * Task 5-f: the ledgered birth — a delivery keyed to its originating
     * event so a re-delivered publication (the framework's own resubmission
     * of an incomplete registry entry) lands on the dedup contract instead
     * of a second row.
     */
    public static Notification create(UUID recipientId, String type, String message,
                                      UUID sourceEventId) {
        return new Notification(UUID.randomUUID(), recipientId, type, message, false, sourceEventId);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getRecipientId() { return recipientId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public boolean isRead() { return read; }
    public UUID getSourceEventId() { return sourceEventId; }
    public void markRead() { this.read = true; }
}
