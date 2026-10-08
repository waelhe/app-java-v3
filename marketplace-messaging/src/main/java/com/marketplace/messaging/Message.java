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

    /**
     * B-04 (compliance plan 0.4): the caller's replay key — the send's
     * deduplication surface (the payment_intents contract mirrored:
     * varchar(64) UNIQUE in V150). Nullable: the WebSocket path and every
     * key-less REST send simply carry no replay surface.
     */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    protected Message() {
    }

    public Message(UUID id, UUID conversationId, UUID senderId, String content) {
        this(id, conversationId, senderId, content, null);
    }

    public Message(UUID id, UUID conversationId, UUID senderId, String content, String idempotencyKey) {
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.content = content;
        this.idempotencyKey = idempotencyKey;
    }

    public static Message create(UUID conversationId, UUID senderId, String content) {
        return create(conversationId, senderId, content, null);
    }

    public static Message create(UUID conversationId, UUID senderId, String content, String idempotencyKey) {
        return new Message(UUID.randomUUID(), conversationId, senderId, content, idempotencyKey);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getConversationId() { return conversationId; }
    public UUID getSenderId() { return senderId; }
    public String getContent() { return content; }
    public boolean isRead() { return read; }
    public String getIdempotencyKey() { return idempotencyKey; }

    public void markRead() { this.read = true; }
}
