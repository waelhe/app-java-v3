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
 * resolves — and the freeze is REAL since ADR-0009: the dispute opening
 * rides the shared {@code DisputeOpenedEvent} (LOAN subject) and the
 * resolution returns the loan to its ACTIVE edge or terminates it through
 * the cancellation edge whose {@code LoanCancelledEvent} drives the ONE
 * refund contract. The DISPUTED loan also STAYS in the live set (ADR-0009:
 * the item is with the borrower — its period stays held against overlapping
 * requests, in {@code isLive()} and in the V177 EXCLUDE rebuild alike).
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

    /** ADR-0009: the owner's per-day late surcharge, frozen at request time. */
    @Column(name = "late_fee_per_day_minor", nullable = false)
    private long lateFeePerDayMinor;

    /** ADR-0009: the settlement's computed lateness (whole days past the period). */
    @Column(name = "late_days", nullable = false)
    private int lateDays;

    /** ADR-0009: the settlement's computed late fee (late days × the frozen rate). */
    @Column(name = "late_fee_minor", nullable = false)
    private long lateFeeMinor;

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
                 Instant startAt, Instant endAt, long feeMinor, String currency,
                 long lateFeePerDayMinor) {
        this.id = id;
        this.productId = productId;
        this.ownerId = ownerId;
        this.borrowerId = borrowerId;
        this.status = LoanStatus.REQUESTED;
        this.startAt = startAt;
        this.endAt = endAt;
        this.feeMinor = feeMinor;
        this.currency = currency;
        this.lateFeePerDayMinor = lateFeePerDayMinor;
    }

    public static Loan request(UUID productId, UUID ownerId, UUID borrowerId,
                               Instant startAt, Instant endAt, long feeMinor, String currency,
                               long lateFeePerDayMinor) {
        if (!startAt.isBefore(endAt)) {
            throw new IllegalArgumentException("The loan period's start must precede its end");
        }
        if (lateFeePerDayMinor < 0) {
            throw new IllegalArgumentException("Lending terms are non-negative minor-unit amounts");
        }
        return new Loan(UUID.randomUUID(), productId, ownerId, borrowerId,
                startAt, endAt, feeMinor, currency, lateFeePerDayMinor);
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
        // The overdue return walks the same edge — ADR-0009: the lateness is
        // derived from the stamped returned_at at settlement (the close edge).
        status = LoanStatus.RETURN_REQUESTED;
    }

    public void confirmReturn(Instant at) {
        require(LoanStatus.RETURN_REQUESTED, "return confirmation");
        status = LoanStatus.RETURNED;
        returnedAt = at;
    }

    /**
     * ADR-0009 (the late-fee rules opened): the settlement computes the
     * lateness from the stamped {@code returned_at} against the period's
     * end — whole days, a partial day rents the whole day (the offer's own
     * {@code feeFor} ceiling semantics verbatim) — and prices it with the
     * OWNER's frozen per-day rate (never caller-supplied; the ADR-0002
     * amount-source lesson). The computed adjustment rides the loan row
     * (Envers-audited) and is collected through the documented follow-up
     * settlement leg (the deposit's leg — ADR-0004 decision 1).
     */
    public void close(Instant at) {
        require(LoanStatus.RETURNED, "settlement close");
        if (returnedAt != null && returnedAt.isAfter(endAt) && lateFeePerDayMinor > 0) {
            long overdue = java.time.Duration.between(endAt, returnedAt).toMillis();
            lateDays = (int) Math.min(Integer.MAX_VALUE,
                    (long) Math.ceil(overdue / 86_400_000.0));
            lateFeeMinor = lateDays * lateFeePerDayMinor;
        }
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

    /**
     * ADR-0009: the dispute freeze — the lending module's dispute listener
     * is the only writer (a dispute opened on the loan through the shared
     * events); the edge is legal from ACTIVE only (ADR-0004's gate kept).
     */
    public void markDisputed() {
        require(LoanStatus.ACTIVE, "dispute");
        status = LoanStatus.DISPUTED;
    }

    /**
     * ADR-0009: the dispute released without a cancellation — the loan
     * resumes the edge it froze (DISPUTED is entered from ACTIVE only, so
     * ACTIVE is the exact inverse; the machine's edges stay paired).
     */
    public void resumeFromDispute() {
        require(LoanStatus.DISPUTED, "dispute resolution (resume)");
        status = LoanStatus.ACTIVE;
    }

    /**
     * ADR-0009: the dispute resolved to the consumer's refund — the loan
     * TERMINATES (the period releases; the money rides the published
     * {@code LoanCancelledEvent} into the payments module's listener — the
     * ONE refund contract; the pre-ADR-0009 machine refused this edge, an
     * ACTIVE loan could never cancel — the dispute resolution is its only
     * controlled path).
     */
    public void cancelFromDispute(String reason, Instant at) {
        require(LoanStatus.DISPUTED, "dispute resolution (refund-cancel)");
        status = LoanStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = at;
    }

    private void require(LoanStatus expected, String transition) {
        if (status != expected) {
            throw new IllegalStateException("Loan " + id + " is " + status
                    + " — " + transition + " is legal from " + expected + " only");
        }
    }

    public boolean isLive() {
        return status == LoanStatus.APPROVED || status == LoanStatus.ACTIVE
                || status == LoanStatus.RETURN_REQUESTED || status == LoanStatus.DISPUTED;
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

    public long getLateFeePerDayMinor() {
        return lateFeePerDayMinor;
    }

    public int getLateDays() {
        return lateDays;
    }

    public long getLateFeeMinor() {
        return lateFeeMinor;
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
