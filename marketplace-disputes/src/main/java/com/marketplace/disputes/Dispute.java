package com.marketplace.disputes;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.util.UUID;

@Entity
@Table(name = "disputes")
@Audited
public class Dispute extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "opened_by", nullable = false)
    private UUID openedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DisputeStatus status;

    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    protected Dispute() {}

    private Dispute(UUID id, UUID bookingId, UUID openedBy, DisputeStatus status, String reason) {
        this.id = id;
        this.bookingId = bookingId;
        this.openedBy = openedBy;
        this.status = status;
        this.reason = reason;
    }

    public static Dispute open(UUID bookingId, UUID openedBy, String reason) {
        return new Dispute(UUID.randomUUID(), bookingId, openedBy, DisputeStatus.OPEN, reason);
    }

    @Override public UUID getId() { return id; }
    public UUID getBookingId(){return bookingId;}
    public void resolve(){
        this.status.validateTransitionTo(DisputeStatus.RESOLVED);
        this.status = DisputeStatus.RESOLVED;
    }
}
