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

import java.util.UUID;

@Entity
@Table(name = "payment_intents")
@Audited
public class PaymentIntent extends BaseEntity {

    /** The verified path's origin — the whole pre-W5 table (see {@link #origin}). */
    static final String ORIGIN_BOOKING = "BOOKING";

    /** W5's ad-bill origin — the payer is the provider, no booking. */
    static final String ORIGIN_AD = "AD";

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

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Column(name = "amount_cents", nullable = false)
    private Long amountCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "SAR";

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
        this(id, bookingId, consumerId, amountCents, null, idempotencyKey);
    }

    PaymentIntent(UUID id, UUID bookingId, UUID consumerId,
                  Long amountCents, String currency, String idempotencyKey) {
        this.id = id;
        this.bookingId = bookingId;
        this.consumerId = consumerId;
        this.amountCents = amountCents;
        this.currency = Currencies.normalizeOrDefault(currency, "SAR");
        this.idempotencyKey = idempotencyKey;
    }

    public static PaymentIntent create(UUID bookingId, UUID consumerId,
                                        Long amountCents, String idempotencyKey) {
        return create(bookingId, consumerId, amountCents, null, idempotencyKey);
    }

    /**
     * Creates an intent denominated in the booking's ISO 4217 currency
     * (roadmap B4) — the money snapshot the PSP charge will carry. Blank/null
     * keeps the house default SAR, exactly the pre-existing behavior.
     */
    public static PaymentIntent create(UUID bookingId, UUID consumerId,
                                        Long amountCents, String currency, String idempotencyKey) {
        return new PaymentIntent(UUID.randomUUID(), bookingId, consumerId, amountCents, currency, idempotencyKey);
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
                                              Long amountCents, String currency, String idempotencyKey) {
        PaymentIntent intent = new PaymentIntent(UUID.randomUUID(), null, providerId,
                amountCents, currency, idempotencyKey);
        intent.origin = ORIGIN_AD;
        intent.adCampaignId = campaignId;
        return intent;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public UUID getConsumerId() { return consumerId; }
    public String getOrigin() { return origin; }
    public UUID getAdCampaignId() { return adCampaignId; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
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
