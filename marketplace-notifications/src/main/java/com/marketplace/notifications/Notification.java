package com.marketplace.notifications;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {
    @Id private UUID id;
    @Column(name = "recipient_id", nullable = false) private UUID recipientId;
    @Enumerated(EnumType.STRING) @Column(name = "channel", nullable = false, length = 20) private NotificationChannel channel;
    @Column(name = "subject", nullable = false, length = 200) private String subject;
    @Column(name = "body", nullable = false, columnDefinition = "TEXT") private String body;
    @Column(name = "read", nullable = false) private boolean read;

    protected Notification() {}
    public Notification(UUID id, UUID recipientId, NotificationChannel channel, String subject, String body) {
        this.id = id; this.recipientId = recipientId; this.channel = channel; this.subject = subject; this.body = body;
    }
    public static Notification create(UUID recipientId, NotificationChannel channel, String subject, String body) {
        return new Notification(UUID.randomUUID(), recipientId, channel, subject, body);
    }
    @Override public UUID getId() { return id; }
    public UUID getRecipientId() { return recipientId; }
    public NotificationChannel getChannel() { return channel; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public boolean isRead() { return read; }
    public void markRead() { this.read = true; }
}
