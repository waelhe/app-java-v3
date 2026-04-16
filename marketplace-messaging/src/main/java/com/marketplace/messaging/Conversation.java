package com.marketplace.messaging;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "participant_a", nullable = false)
    private UUID participantA;

    @Column(name = "participant_b", nullable = false)
    private UUID participantB;

    protected Conversation() {
    }

    public Conversation(UUID id, UUID bookingId, UUID participantA, UUID participantB) {
        this.id = id;
        this.bookingId = bookingId;
        this.participantA = participantA;
        this.participantB = participantB;
    }

    public static Conversation create(UUID participantA, UUID participantB, UUID bookingId) {
        return new Conversation(UUID.randomUUID(), bookingId, participantA, participantB);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getParticipantA() { return participantA; }
    public UUID getParticipantB() { return participantB; }

    public boolean hasParticipant(UUID userId) {
        return participantA.equals(userId) || participantB.equals(userId);
    }
}