package com.marketplace.disputes;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "disputes")
public class Dispute extends BaseEntity {
    @Id private UUID id;
    @Column(name = "booking_id", nullable = false) private UUID bookingId;
    @Column(name = "opened_by", nullable = false) private UUID openedBy;
    @Column(name = "reason", nullable = false, length = 500) private String reason;
    @Column(name = "resolution", length = 1000) private String resolution;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false, length = 32) private DisputeStatus status = DisputeStatus.OPEN;
    protected Dispute() {}
    public Dispute(UUID id, UUID bookingId, UUID openedBy, String reason) { this.id = id; this.bookingId = bookingId; this.openedBy = openedBy; this.reason = reason; }
    public static Dispute create(UUID bookingId, UUID openedBy, String reason) { return new Dispute(UUID.randomUUID(), bookingId, openedBy, reason); }
    @Override public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getOpenedBy() { return openedBy; }
    public String getReason() { return reason; }
    public String getResolution() { return resolution; }
    public DisputeStatus getStatus() { return status; }
    public void resolve(String resolution) { this.resolution = resolution; this.status = DisputeStatus.RESOLVED; }
    public void reject(String resolution) { this.resolution = resolution; this.status = DisputeStatus.REJECTED; }
}
