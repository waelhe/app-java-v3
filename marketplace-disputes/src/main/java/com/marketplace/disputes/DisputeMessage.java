package com.marketplace.disputes;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "dispute_messages")
public class DisputeMessage extends BaseEntity {
    @Id private UUID id;
    @Column(name = "dispute_id", nullable = false) private UUID disputeId;
    @Column(name = "sender_id", nullable = false) private UUID senderId;
    @Column(name = "message", nullable = false, columnDefinition = "TEXT") private String message;
    protected DisputeMessage() {}
    public DisputeMessage(UUID id, UUID disputeId, UUID senderId, String message) { this.id = id; this.disputeId = disputeId; this.senderId = senderId; this.message = message; }
    public static DisputeMessage create(UUID disputeId, UUID senderId, String message) { return new DisputeMessage(UUID.randomUUID(), disputeId, senderId, message); }
    @Override public UUID getId() { return id; }
    public UUID getDisputeId() { return disputeId; }
    public UUID getSenderId() { return senderId; }
    public String getMessage() { return message; }
}
