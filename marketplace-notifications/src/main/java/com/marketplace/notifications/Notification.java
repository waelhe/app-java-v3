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

    protected Notification() {}

    private Notification(UUID id, UUID recipientId, String type, String message, boolean read) {
        this.id = id;
        this.recipientId = recipientId;
        this.type = type;
        this.message = message;
        this.read = read;
    }

    public static Notification create(UUID recipientId, String type, String message) {
        return new Notification(UUID.randomUUID(), recipientId, type, message, false);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getRecipientId() { return recipientId; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public boolean isRead() { return read; }
    public void markRead() { this.read = true; }
}
