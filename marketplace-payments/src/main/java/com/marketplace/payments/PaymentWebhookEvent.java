package com.marketplace.payments;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.envers.Audited;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * R10 (comprehensive-review-ar fix plan §4/R10 — Wave 2): the webhook event
 * row grows from a bare dedup tombstone into the durable inbox — the row now
 * carries the complete re-delivery contract (event type, resolved intent,
 * external id, refund snapshot amount) plus the raw provider payload for
 * inspection, and a {@link WebhookProcessingState} lifecycle the recovery
 * sweep ({@code WebhookInboxRecovery}) closes over the crash window the
 * tombstone used to leave open (recorded-and-lost).
 *
 * <p><b>Payload storage (the V48/amenities and V54/saved-searches
 * precedent):</b> the raw provider notification is kept as JSONB through the
 * official Hibernate JSON mapping — a structured, queryable column, not a
 * text blob. The Java side is {@code String} because the payload arrives as
 * the already-verified raw body ({@code PaymentsController.stripeWebhook}
 * reads it byte-identical for signature verification); the legacy HMAC route
 * carries no body at all, so the column is nullable by contract.
 *
 * <p><b>Soft delete:</b> inherited from {@link BaseEntity} — the retention
 * purge of SETTLED rows is a hard {@code DELETE} through JDBC (the
 * {@code ExpiredAuthorizationsCleanup} pattern), so soft-deleted and purged
 * rows are the same thing to every query: gone.
 */
@Entity
@Table(name = "payment_webhook_events")
@Audited
public class PaymentWebhookEvent extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "event_id", nullable = false, length = 200)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * The inbox lifecycle — never written by a silent database default: the
     * factory below is the only creator and it always states
     * {@link WebhookProcessingState#RECEIVED} explicitly (V71 dropped the
     * backfill default precisely so a forgetful future writer fails loudly).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_state", nullable = false, length = 20)
    private WebhookProcessingState processingState;

    /**
     * The raw provider notification, kept byte-identical for inspection.
     * String + {@code SqlTypes.JSON} is the RAW-document mapping, verified
     * against the shipped Hibernate 7.4.5 bytecode:
     * {@code AbstractJsonFormatMapper.toString} returns the value as-is when
     * the Java type is String (and {@code fromString} parses nothing) — the
     * JSON document is the string itself, never a double-encoded JSON string
     * (the same bytecode-verification discipline the repository applies to
     * framework claims; the integration test asserts the stored column
     * round-trips and parses as a JSON object).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private String payload;

    /** The dispatch's resolved intent (nullable: not every event resolves one). */
    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    /** The provider's intent id as the dispatch saw it (V33 link side). */
    @Column(name = "external_id", length = 200)
    private String externalId;

    /**
     * The refund snapshot's cumulative amount for {@code charge.refunded}
     * rows — the dispatch input the recovery sweep replays without the
     * channel being bound (the raw payload stays the human-readable source).
     */
    @Column(name = "refund_amount_cents")
    private Long refundAmountCents;

    /** Why a recovery re-delivery terminally failed — the operator signal. */
    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    protected PaymentWebhookEvent() {}

    private PaymentWebhookEvent(UUID id, String provider, String eventId, String eventType,
                                String payload, UUID paymentIntentId, String externalId,
                                Long refundAmountCents) {
        this.id = id;
        this.provider = provider;
        this.eventId = eventId;
        this.eventType = eventType;
        this.processingState = WebhookProcessingState.RECEIVED;
        this.payload = payload;
        this.paymentIntentId = paymentIntentId;
        this.externalId = externalId;
        this.refundAmountCents = refundAmountCents;
    }

    /**
     * The inbox's single creation point: every row is born RECEIVED with the
     * complete re-delivery contract. The unique {@code (provider, event_id)}
     * key (V42) keeps this insert the serialization point between concurrent
     * deliveries — unchanged dedup semantics (CodeRabbit #241).
     */
    public static PaymentWebhookEvent create(String provider, String eventId, String eventType,
                                             String payload, UUID paymentIntentId,
                                             String externalId, Long refundAmountCents) {
        return new PaymentWebhookEvent(UUID.randomUUID(), provider, eventId, eventType,
                payload, paymentIntentId, externalId, refundAmountCents);
    }

    /** Terminal success — written inside the dispatch's transaction (R10). */
    void markSettled() {
        this.processingState = WebhookProcessingState.SETTLED;
    }

    /** Terminal, inspectable failure — written by the recovery sweep only. */
    void markFailed(String reason) {
        this.processingState = WebhookProcessingState.FAILED;
        this.failureReason = reason != null && reason.length() > 1000
                ? reason.substring(0, 1000)
                : reason;
    }

    @Override
    public UUID getId() { return id; }
    public String getProvider() { return provider; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public WebhookProcessingState getProcessingState() { return processingState; }
    public String getPayload() { return payload; }
    public UUID getPaymentIntentId() { return paymentIntentId; }
    public String getExternalId() { return externalId; }
    public Long getRefundAmountCents() { return refundAmountCents; }
    public String getFailureReason() { return failureReason; }
}
