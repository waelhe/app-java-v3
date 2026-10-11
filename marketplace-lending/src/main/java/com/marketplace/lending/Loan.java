package com.marketplace.lending;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the loan — the lending workflow's own
 * aggregate. The item is the storefront's Product (a plain UUID column;
 * the catalog module owns product identity — the V32 discipline), the
 * owner is denormalized at request time (the ledger's credit fact), the
 * period is the two-instant record the EXCLUDE constraint serializes,
 * and the fee is minor-unit money (the house convention — no float).
 *
 * <p>The machine (guarded in {@code LendingService}, each transition
 * stamped and audited by Envers — the A-11 machine's discipline verbatim):
 *
 * <pre>
 *   request          approve            handover (paid)      return         settle
 *   [borrower] ─▶ REQUESTED ──▶ APPROVED ──▶ ACTIVE ──▶ RETURNED ──▶ CLOSED
 *                    │  │            │
 *                    │  │ decline    │ cancel (borrower)
 *                    │  ▼            ▼
 *                    │ DECLINED   CANCELLED
 *                    │ cancel (borrower) ▲
 *                    └───────────────────┘        ACTIVE ──▶ DISPUTED
 * </pre>
 *
 * <p>{@code CLOSED}, {@code DECLINED}, {@code CANCELLED} are terminal;
 * {@code DISPUTED} freezes the ACTIVE loan's edges until the dispute
 * resolves (the disputes module's opening seam is the documented
 * deferral — the loan-side state and the event exist now).
 */
@Entity
@Table(name = "loans")
@Audited
public class Loan extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "borrower_id", nullable = false)
    private UUID borrowerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LoanStatus status;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "fee_minor", nullable = false)
    private Long feeMinor;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "handover_at")
    private Instant handoverAt;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    protected Loan() {
        // JPA
    }

    private Loan(UUID id, UUID productId, UUID ownerId, UUID borrowerId,
                 Instant startAt, Instant endAt, long feeMinor, String currency) {
        this.id = id;
        this.productId = productId;
        this.ownerId = ownerId;
        this.borrowerId = borrowerId;
        this.status = LoanStatus.REQUESTED;
        this.startAt = startAt;
        this.endAt = endAt;
        this.feeMinor = feeMinor;
        this.currency = currency;
    }

    public static Loan request(UUID productId, UUID ownerId, UUID borrowerId,
                               Instant startAt, Instant endAt, long feeMinor, String currency) {
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("The loan period's start must precede its end");
        }
        return new Loan(UUID.randomUUID(), productId, ownerId, borrowerId,
                startAt, endAt, feeMinor, currency);
    }

    // -- the guarded edges (the service is the single writer) ------------

    public void approve() {
        require(LoanStatus.REQUESTED, "approve");
        status = LoanStatus.APPROVED;
    }

    public void decline() {
        require(LoanStatus.REQUESTED, "decline");
        status = LoanStatus.DECLINED;
    }

    public void markPaid(Instant at) {
        require(LoanStatus.APPROVED, "payment settlement");
        paidAt = at;
    }

    public void linkPaymentIntent(UUID paymentIntentId) {
        this.paymentIntentId = paymentIntentId;
    }

    /** The handover — legal only once the fee's intent settled (the paid gate). */
    public void activate(Instant at) {
        require(LoanStatus.APPROVED, "handover");
        if (paidAt == null) {
            throw new IllegalStateException("Loan " + id + " handover requires the settled fee");
        }
        status = LoanStatus.ACTIVE;
        handoverAt = at;
    }

    public void requestReturn(Instant at) {
        require(LoanStatus.ACTIVE, "return request");
        if (!Instant.now().isBefore(endAt)) {
            // The overdue return still walks the same edge — the ADR's
            // delay handling records the lateness on settlement.
        }
        status = LoanStatus.RETURN_REQUESTED;
    }

    public void confirmReturn(Instant at) {
        require(LoanStatus.RETURN_REQUESTED, "return confirmation");
        status = LoanStatus.RETURNED;
        returnedAt = at;
    }

    public void close(Instant at) {
        require(LoanStatus.RETURNED, "settlement close");
        status = LoanStatus.CLOSED;
        closedAt = at;
    }

    public void cancel(String reason, Instant at) {
        if (status != LoanStatus.REQUESTED && status != LoanStatus.APPROVED) {
            throw new IllegalStateException("Loan " + id + " is " + status
                    + " — cancel is legal from REQUESTED or APPROVED only");
        }
        status = LoanStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = at;
    }

    public void markDisputed() {
        require(LoanStatus.ACTIVE, "dispute");
        status = LoanStatus.DISPUTED;
    }

    private void require(LoanStatus expected, String transition) {
        if (status != expected) {
            throw new IllegalStateException("Loan " + id + " is " + status
                    + " — " + transition + " is legal from " + expected + " only");
        }
    }

    public boolean isLive() {
        return status == LoanStatus.APPROVED || status == LoanStatus.ACTIVE
                || status == LoanStatus.RETURN_REQUESTED;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public UUID getBorrowerId() {
        return borrowerId;
    }

    public LoanStatus getStatus() {
        return status;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public Long getFeeMinor() {
        return feeMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public UUID getPaymentIntentId() {
        return paymentIntentId;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
