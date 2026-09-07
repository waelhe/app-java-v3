package com.marketplace.disputes;

import com.marketplace.shared.api.ConflictException;
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

    /** L24 — the resolve decision's outcome; null while OPEN. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", length = 20)
    private DisputeResolution resolution;

    /**
     * L24 — the refunded payment the dispute links to (the dispute row IS
     * the dispute &lt;-&gt; refund linkage); null unless the decision was
     * {@code REFUND_CONSUMER}.
     */
    @Column(name = "refund_payment_id")
    private UUID refundPaymentId;

    /** L24 — the payment's cumulative refunded total after the execution. */
    @Column(name = "refunded_amount_cents")
    private Long refundedAmountCents;

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
    public UUID getOpenedBy() { return openedBy; }
    public DisputeStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public DisputeResolution getResolution() { return resolution; }
    public UUID getRefundPaymentId() { return refundPaymentId; }
    public Long getRefundedAmountCents() { return refundedAmountCents; }

    /**
     * L24: resolves with the decision's outcome. The OPEN -&gt; RESOLVED
     * validation runs FIRST — a repeated resolve request is a 409 BEFORE
     * any money moves (roadmap §5-L24 acceptance 1).
     */
    public void resolve(DisputeResolution resolution){
        this.status.validateTransitionTo(DisputeStatus.RESOLVED);
        this.status = DisputeStatus.RESOLVED;
        this.resolution = resolution;
    }

    /**
     * L24: records the executed refund movement on the dispute — the
     * linkage to the refunded payment and its cumulative refunded total
     * at execution time.
     */
    public void recordRefund(UUID paymentId, long refundedAmountCents){
        if (this.resolution != DisputeResolution.REFUND_CONSUMER) {
            throw new ConflictException("Refund can only be recorded on a REFUND_CONSUMER resolution, not: " + this.resolution);
        }
        this.refundPaymentId = paymentId;
        this.refundedAmountCents = refundedAmountCents;
    }
}
