package com.marketplace.payments;

import com.marketplace.shared.api.Currencies;
import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "payment_intents")
@Audited
public class PaymentIntent extends BaseEntity {

    /** The verified path's origin — the whole pre-W5 table (see {@link #origin}). */
    static final String ORIGIN_BOOKING = "BOOKING";

    /** W5's ad-bill origin — the payer is the provider, no booking. */
    static final String ORIGIN_AD = "AD";

    /** Stage 6 (ADR-0002): the order-checkout origin — the payer is the buyer, the subject is the order. */
    static final String ORIGIN_ORDER = "ORDER";

    /** Stage 8 (ADR-0004): the loan-fee origin — the payer is the borrower, the subject is the loan. */
    static final String ORIGIN_LOAN = "LOAN";
    static final String ORIGIN_DELIVERY = "DELIVERY";

    @Id
    private UUID id;

    @Column(name = "booking_id")
    private UUID bookingId;

    /**
     * W5 (yelp-level plan §5 — the ads & billing wave): the intent's
     * origin — the plan's §4.2 explicit shape held by the DB CHECK
     * ({@code chk_payment_intents_origin}), not a Java enum (the W1
     * review-origin decision, verbatim). 'BOOKING' is the whole pre-W5
     * table; 'AD' is the ad-bill path whose payer is the provider.
     */
    @Column(name = "origin", nullable = false, length = 12)
    private String origin = ORIGIN_BOOKING;

    /**
     * W5: the ad campaign this intent bills — null iff origin is BOOKING
     * (the cross-column CHECK {@code ck_payment_intents_origin_pairing}
     * pins the pairing at the database level).
     */
    @Column(name = "ad_campaign_id")
    private UUID adCampaignId;

    /**
     * Stage 6 (ADR-0002): the order this intent pays for — non-null iff
     * origin is ORDER (the V184 pairing CHECK pins the pairing at the
     * database level). One intent per order is the partial-unique index's
     * own rule; the deterministic {@code order-…} idempotency key is the
     * first line of defense.
     */
    @Column(name = "order_id")
    private UUID orderId;

    /**
     * Stage 8 (ADR-0004): the loan this intent's fee pays for — non-null
     * iff origin is LOAN (the V200 pairing CHECK). One intent per loan is
     * the partial-unique index's own rule.
     */
    @Column(name = "loan_id")
    private UUID loanId;

    @Column(name = "delivery_request_id")
    private UUID deliveryRequestId;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "SAR";

    /**
     * ADR-0013 (D2 — the commission freeze): the announced commission rate
     * frozen at THIS intent's creation — Stripe's application-fee model:
     * {@code application_fee_amount} is fixed at PaymentIntent creation and
     * never recomputed at settlement, so the fee the parties agreed to is
     * the fee collected. The ledger's completion listener posts THIS value;
     * a rate change affects new intents only.
     *
     * <p>NULL carries two honest meanings: the pre-V220 rows (their
     * creation-time rate is unknowable after the fact — a DEFAULT would lie
     * about history exactly the way V82 rejected a currency DEFAULT) and
     * test fixtures that predate the freeze. Every intent the runtime
     * creates through the factories below carries a frozen value; the
     * ledger falls back to the live configuration only for the NULL
     * legacy rows (the ADR's own interim rule, made structural).
     */
    @Column(name = "commission_rate", precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentIntentStatus status = PaymentIntentStatus.CREATED;

    @Column(name = "refunded_amount_cents", nullable = false)
    private Long refundedAmountCents = 0L;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    /**
     * Id of the remote PaymentIntent at the PSP (e.g. {@code pi_...}). Filled
     * once when the remote intent is created via the PspChannel; webhooks
     * resolve the local intent through it (V33). Nullable — the column only
     * carries a value when the real payment channel is bound.
     */
    @Column(name = "psp_intent_id", length = 100)
    private String pspIntentId;

    protected PaymentIntent() {
    }

    public PaymentIntent(UUID id, UUID bookingId, UUID consumerId,
                         Long amountCents, String idempotencyKey) {
        this(id, bookingId, consumerId, amountCents, null, idempotencyKey, null);
    }

    PaymentIntent(UUID id, UUID bookingId, UUID consumerId,
                  Long amountCents, String currency, String idempotencyKey,
                  BigDecimal commissionRate) {
        this.id = id;
        this.bookingId = bookingId;
        this.consumerId = consumerId;
        this.amountCents = amountCents;
        this.currency = Currencies.normalizeOrDefault(currency, "SAR");
        this.idempotencyKey = idempotencyKey;
        this.commissionRate = commissionRate;
    }

    /**
     * Creates an intent denominated in the booking's ISO 4217 currency
     * (roadmap B4) — the money snapshot the PSP charge will carry. Blank/null
     * keeps the house default SAR, exactly the pre-existing behavior.
     *
     * <p>ADR-0013 (D2): {@code commissionRate} is the announced rate frozen
     * at THIS creation — the caller (the payments service) reads it from the
     * {@code app.commission} configuration; the ledger posts the frozen
     * value, never a recomputation.
     */
    public static PaymentIntent create(UUID bookingId, UUID consumerId,
                                        Long amountCents, String currency, String idempotencyKey,
                                        BigDecimal commissionRate) {
        return new PaymentIntent(UUID.randomUUID(), bookingId, consumerId, amountCents, currency,
                idempotencyKey, commissionRate);
    }

    /**
     * W5 (yelp-level plan §5 — «وظيفة خصم دورية تُصدر نية دفع»): the ad
     * bill's intent. The payer is the PROVIDER (consumer_id carries the
     * provider's user id — the column's "payer" semantics are exactly
     * what the ad bill needs), the booking coupling is lifted the way
     * W1 lifted the review's (origin + cross-column CHECK), and the
     * idempotency key is the DETERMINISTIC window key
     * {@code ad-debit-{campaignId}-{windowStart}} — the listener derives
     * it, the column's UNIQUE index rejects the replay.
     */
    public static PaymentIntent createForAds(UUID providerId, UUID campaignId,
                                              Long amountCents, String currency, String idempotencyKey,
                                              BigDecimal commissionRate) {
        PaymentIntent intent = new PaymentIntent(UUID.randomUUID(), null, providerId,
                amountCents, currency, idempotencyKey, commissionRate);
        intent.origin = ORIGIN_AD;
        intent.adCampaignId = campaignId;
        return intent;
    }

    /**
     * Stage 6 (ADR-0002): the order-checkout intent. The payer is the
     * buyer (consumer_id carries the order's consumer), the subject is
     * the order (no booking, no campaign — the V184 pairing CHECK), and
     * the idempotency key is the DETERMINISTIC order key
     * {@code order-{orderId}} — the engine deduplicates on it.
     */
    public static PaymentIntent createForOrder(UUID orderId, UUID consumerId,
                                               Long amountCents, String currency, String idempotencyKey,
                                               BigDecimal commissionRate) {
        PaymentIntent intent = new PaymentIntent(UUID.randomUUID(), null, consumerId,
                amountCents, currency, idempotencyKey, commissionRate);
        intent.origin = ORIGIN_ORDER;
        intent.orderId = orderId;
        return intent;
    }

    /**
     * Stage 8 (ADR-0004): the loan-fee intent — the order-checkout twin
     * verbatim: the payer is the borrower, the subject is the loan, and
     * the idempotency key is the DETERMINISTIC loan key
     * {@code loan-{loanId}}.
     */
    public static PaymentIntent createForLoan(UUID loanId, UUID borrowerId,
                                              Long amountCents, String currency, String idempotencyKey,
                                              BigDecimal commissionRate) {
        PaymentIntent intent = new PaymentIntent(UUID.randomUUID(), null, borrowerId,
                amountCents, currency, idempotencyKey, commissionRate);
        intent.origin = ORIGIN_LOAN;
        intent.loanId = loanId;
        return intent;
    }

    /**
     * The direct-delivery origin (the platform-wide payments plan): the
     * payer is the requesting member, the subject is the delivery request,
     * and the deterministic {@code delivery-{deliveryRequestId}} key is
     * the engine's deduplication — the four siblings' pattern verbatim.
     *
     * <p>ADR-0013 (D2) freezes the announced commission rate at creation
     * for every intent the platform creates — for THIS origin the frozen
     * value is honestly NULL: the platform-operated delivery mode (D4)
     * books the courier's payable in the dedicated delivery journal, not
     * a provider-allocation commission debit, so there is no announced
     * commission to freeze. The ledger's delivery branch never reads the
     * rate — the same fail-closed rule the origin dispatch enforces.
     */
    public static PaymentIntent createForDelivery(UUID deliveryRequestId, UUID requesterId,
                                                  Long amountMinor, String currency, String idempotencyKey) {
        PaymentIntent intent = new PaymentIntent(UUID.randomUUID(), null, requesterId, amountMinor, currency,
                idempotencyKey, null);
        intent.origin = ORIGIN_DELIVERY;
        intent.deliveryRequestId = deliveryRequestId;
        return intent;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getOrderId() { return orderId; }
    public UUID getLoanId() { return loanId; }
    public UUID getDeliveryRequestId() { return deliveryRequestId; }
    public UUID getConsumerId() { return consumerId; }
    public String getOrigin() { return origin; }
    public UUID getAdCampaignId() { return adCampaignId; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }

    /** ADR-0013 (D2): the frozen rate — null on pre-V220 rows and pre-freeze fixtures. */
    public BigDecimal getCommissionRate() { return commissionRate; }
    public PaymentIntentStatus getStatus() { return status; }
    public Long getRefundedAmountCents() { return refundedAmountCents; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPspIntentId() { return pspIntentId; }

    /**
     * Links this intent to its remote PSP counterpart. Idempotent: a repeated
     * call with the same id keeps the link, a conflicting id is rejected —
     * one local intent maps to exactly one remote intent.
     */
    public void assignPspIntentId(String pspIntentId) {
        if (this.pspIntentId != null && !this.pspIntentId.equals(pspIntentId)) {
            throw new IllegalStateException(
                    "Payment intent already linked to a different PSP intent: " + this.pspIntentId);
        }
        this.pspIntentId = pspIntentId;
    }

    public void markProcessing() {
        this.status.validateTransitionTo(PaymentIntentStatus.PROCESSING);
        this.status = PaymentIntentStatus.PROCESSING;
    }

    public void markSucceeded() {
        this.status.validateTransitionTo(PaymentIntentStatus.SUCCEEDED);
        this.status = PaymentIntentStatus.SUCCEEDED;
    }

    public void markFailed() {
        this.status.validateTransitionTo(PaymentIntentStatus.FAILED);
        this.status = PaymentIntentStatus.FAILED;
    }

    public void cancel() {
        this.status.validateTransitionTo(PaymentIntentStatus.CANCELLED);
        this.status = PaymentIntentStatus.CANCELLED;
    }

    public void markRefunded() {
        this.status.validateTransitionTo(PaymentIntentStatus.REFUNDED);
        this.status = PaymentIntentStatus.REFUNDED;
        this.refundedAmountCents = this.amountCents;
    }

    public void markPartiallyRefunded(Long refundAmountCents) {
        this.status.validateTransitionTo(PaymentIntentStatus.PARTIALLY_REFUNDED);
        this.status = PaymentIntentStatus.PARTIALLY_REFUNDED;
        this.refundedAmountCents += refundAmountCents;
    }

    /**
     * L19: partial refund whose amount is the remote provider's cumulative
     * actual — SET (not summed) to the remote total, mirroring
     * {@code Payment.markPartiallyRefundedTotal}.
     */
    public void markPartiallyRefundedTotal(long refundedTotalCents) {
        this.status.validateTransitionTo(PaymentIntentStatus.PARTIALLY_REFUNDED);
        this.status = PaymentIntentStatus.PARTIALLY_REFUNDED;
        this.refundedAmountCents = refundedTotalCents;
    }
}
