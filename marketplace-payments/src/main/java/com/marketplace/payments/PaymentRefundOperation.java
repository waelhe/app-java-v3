package com.marketplace.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Review round 1 (the payments trio): the durable journal of a
 * dispute-scoped remote refund operation — the local record of an
 * operation whose execution lives behind the provider's idempotency
 * layer, a layer that is invisible to this side of the channel and
 * expires after 24 hours.
 *
 * <p><b>The lifecycle:</b> a row is born in its own REQUIRES_NEW
 * transaction at the moment the dispute refund is first submitted
 * (surviving the resolve transaction's rollback window by construction),
 * freezing the submitted amount and the local books as they stood at
 * submission; the remote refund's identifier and the EXECUTED status are
 * recorded the moment the provider answers. A retried submission reads
 * the frozen record instead of trusting the request's parameters.</p>
 *
 * <p><b>Deliberately NOT a {@code BaseEntity} and NOT Envers-audited:</b>
 * this is an operational journal of provider-side facts, not domain
 * state — the V33 {@code _aud}-twin lesson applies to {@code @Audited}
 * entities only, and the optimistic-lock {@code @Version} column has no
 * role in a single-writer journal whose writes ride dedicated
 * transactions.</p>
 */
@Entity
@Table(name = "payment_refund_operations")
public class PaymentRefundOperation {

    /** The dispute-scoped idempotency key — the operation's identity. */
    @Id
    @Column(name = "idempotency_key", length = 200, nullable = false)
    private String idempotencyKey;

    /** The payment whose channel the refund was submitted through. */
    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    /**
     * The amount the FIRST submission carried (the house's smallest
     * currency unit) — {@code null} mirrors a full-remaining refund
     * submission.
     */
    @Column(name = "amount_cents")
    private Long amountCents;

    /**
     * The payment's cumulative refunded total AS THE BOOKS STOOD at the
     * first submission — the executed-test's baseline: once the
     * webhook-synced books reach {@code booksAtSubmitCents +
     * amountCents}, this operation's refund is part of the remote
     * actual.
     */
    @Column(name = "books_at_submit_cents", nullable = false)
    private long booksAtSubmitCents;

    /** The provider's refund identifier, once known. */
    @Column(name = "remote_refund_id", length = 100)
    private String remoteRefundId;

    /** SUBMITTED, then EXECUTED once the provider answers with success. */
    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PaymentRefundOperation() {}

    PaymentRefundOperation(String idempotencyKey, UUID paymentId, Long amountCents, long booksAtSubmitCents) {
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.amountCents = amountCents;
        this.booksAtSubmitCents = booksAtSubmitCents;
        this.status = "SUBMITTED";
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The operation's identity — the dispute-scoped idempotency key. */
    public String getIdempotencyKey() { return idempotencyKey; }

    public UUID getPaymentId() { return paymentId; }

    /** The frozen amount ({@code null} = the full-remaining submission). */
    public Long getAmountCents() { return amountCents; }

    /** The books-at-submission baseline for the executed-test. */
    public long getBooksAtSubmitCents() { return booksAtSubmitCents; }

    public String getRemoteRefundId() { return remoteRefundId; }

    public String getStatus() { return status; }

    /** Records the provider's answer — the refund executed remotely. */
    void markExecuted(String remoteRefundId) {
        this.remoteRefundId = remoteRefundId;
        this.status = "EXECUTED";
        this.updatedAt = Instant.now();
    }
}
