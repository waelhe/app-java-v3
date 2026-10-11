package com.marketplace.disputes;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.DisputeResolution;
import com.marketplace.shared.api.DisputeSubject;
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

    /**
     * ADR-0009 (the subject generalization): nullable since the loan
     * subjects exist — the V177 pairing constraint is the row-level twin
     * (exactly one of booking_id/loan_id is set, matching the subject).
     */
    @Column(name = "booking_id")
    private UUID bookingId;

    /** ADR-0009: the loan subject's id (null on the booking subjects). */
    @Column(name = "loan_id")
    private UUID loanId;

    /** ADR-0009: which subject this dispute rides (V177's default: BOOKING). */
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private DisputeSubject subjectType;

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
     * {@code REFUND_CONSUMER} on a BOOKING subject.
     */
    @Column(name = "refund_payment_id")
    private UUID refundPaymentId;

    /** L24 — the payment's cumulative refunded total after the execution. */
    @Column(name = "refunded_amount_cents")
    private Long refundedAmountCents;

    protected Dispute() {}

    private Dispute(UUID id, DisputeSubject subjectType, UUID bookingId, UUID loanId,
                    UUID openedBy, DisputeStatus status, String reason) {
        this.id = id;
        this.subjectType = subjectType;
        this.bookingId = bookingId;
        this.loanId = loanId;
        this.openedBy = openedBy;
        this.status = status;
        this.reason = reason;
    }

    /** The V20 original — the booking subject. */
    public static Dispute openBooking(UUID bookingId, UUID openedBy, String reason) {
        return new Dispute(UUID.randomUUID(), DisputeSubject.BOOKING, bookingId, null,
                openedBy, DisputeStatus.OPEN, reason);
    }

    /** ADR-0009 — the loan subject (the lending workflow's damage dispute). */
    public static Dispute openLoan(UUID loanId, UUID openedBy, String reason) {
        return new Dispute(UUID.randomUUID(), DisputeSubject.LOAN, null, loanId,
                openedBy, DisputeStatus.OPEN, reason);
    }

    @Override public UUID getId() { return id; }
    public DisputeSubject getSubjectType() { return subjectType; }
    public UUID getBookingId(){return bookingId;}
    public UUID getLoanId() { return loanId; }
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
     * at execution time. A LOAN subject never records here: its money
     * rides the lending module's own cancellation chain (ADR-0009).
     */
    public void recordRefund(UUID paymentId, long refundedAmountCents){
        if (this.resolution != DisputeResolution.REFUND_CONSUMER) {
            throw new ConflictException("Refund can only be recorded on a REFUND_CONSUMER resolution, not: " + this.resolution);
        }
        this.refundPaymentId = paymentId;
        this.refundedAmountCents = refundedAmountCents;
    }
}
