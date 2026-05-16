package com.marketplace.messaging;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

@Entity
@Table(name = "messages")
@Audited
public class Message extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "read", nullable = false)
    private boolean read = false;

    protected Message() {
    }

    public Message(UUID id, UUID conversationId, UUID senderId, String content) {
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
    }

    public static Message create(UUID conversationId, UUID senderId, String content) {
        return new Message(UUID.randomUUID(), conversationId, senderId, content);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public UUID getSenderId() { return senderId; }
    public String getContent() { return content; }
    public boolean isRead() { return read; }

    public void markRead() { this.read = true; }
}