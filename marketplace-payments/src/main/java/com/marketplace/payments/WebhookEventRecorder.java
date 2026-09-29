package com.marketplace.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Deduplication gate for provider webhook events (CodeRabbit #241: the
 * findByProviderAndEventId-then-save sequence was check-then-act — two
 * concurrent deliveries of the same event could both pass the lookup and both
 * dispatch the payment transition, the loser then failing on the unique
 * {@code (provider, event_id)} write with a 5xx).
 *
 * <p>Gate scope (B5): the unique key is provider-scoped (V42) — dedup applies
 * per channel, so migrate/postgreSQL-scoped lookups carry the provider.</p>
 *
 * <p>{@link #record} runs in its own transaction ({@code REQUIRES_NEW}) and
 * flushes the insert immediately, so the unique index — not a post-commit
 * constraint violation — decides which concurrent delivery owns the event.
 * The losing insert surfaces as {@link DataIntegrityViolationException} at
 * this component's transactional boundary (a PostgreSQL transaction is
 * aborted after a failed statement, so the exception must be caught OUTSIDE
 * the boundary — in {@code PaymentsService.handleVerifiedWebhook} — where the
 * caller's transaction is still clean) and is answered as "already
 * processed", never a 5xx.</p>
 *
 * <p><b>R10 (Wave 2) — the row is now the durable inbox, not a tombstone.</b>
 * {@link #record} saves the complete re-delivery contract (raw payload,
 * resolved intent, external id, refund snapshot amount) with the state
 * {@link WebhookProcessingState#RECEIVED RECEIVED}. The crash window between
 * this commit and the dispatch's completion — the window where a worker stop
 * used to strand the dedup row forever while the settlement never happened —
 * is closed by the recovery sweep ({@code WebhookInboxRecovery}): a RECEIVED
 * row older than the staleness threshold is re-delivered. The state's
 * terminal transitions are written by this component:
 * <ul>
 *   <li>{@link #markSettled} — {@code Propagation.REQUIRED} <i>by design</i>:
 *       called from inside the settlement's own transaction
 *       ({@code PaymentIntentSettlementService.confirm/fail}) it joins that
 *       transaction, so "the row is SETTLED" and "the settlement committed"
 *       are one atomic fact — the exact atomicity the fix plan demands
 *       ("التسوية تنقل الصف إلى SETTLED في نفس معاملة تسوية الدفعة"). The
 *       non-settlement dispatch types call it after their effects inside the
 *       carrier's transaction (idempotent — a second call on a SETTLED row is
 *       a no-op).</li>
 *   <li>{@link #markFailed} — the recovery sweep's terminal, inspectable
 *       failure state: the row keeps the reason for the operator. Never
 *       purged by the retention sweep.</li>
 * </ul>
 *
 * <p>{@link #delete} is the compensating action for a dispatch that failed
 * while the worker was alive: the event row is committed BEFORE the caller
 * dispatches, so a dispatch that fails and rolls back the caller's
 * transaction must also remove the row — otherwise the provider's retry
 * would hit the dedup gate for an event that was never processed. A crash
 * <i>between</i> record and dispatch leaves the row RECEIVED (recovered), a
 * live failure leaves no row at all (the provider retries) — the two failure
 * families each keep their existing, documented semantics.</p>
 */
@Component
class WebhookEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventRecorder.class);

    private final PaymentWebhookEventRepository repository;

    WebhookEventRecorder(PaymentWebhookEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Inserts the inbox row and COMMITS it before returning — the caller may
     * only dispatch after this succeeds. The unique (provider, event_id) index
     * makes the insert the serialization point between concurrent deliveries.
     * The row is born RECEIVED carrying the full re-delivery contract (R10).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String provider, String eventId, String eventType, String payload,
                       UUID paymentIntentId, String externalId, Long refundAmountCents) {
        repository.saveAndFlush(PaymentWebhookEvent.create(
                provider, eventId, eventType, payload, paymentIntentId, externalId, refundAmountCents));
        log.debug("Recorded webhook event {} ({} {}) as the inbox row (RECEIVED)", eventId, provider, eventType);
    }

    /**
     * Terminal success — REQUIRED by design: it joins the caller's
     * transaction, which for the settlement events IS the settlement's
     * transaction (atomicity), and for the backstop call after a non-settlement
     * dispatch is the carrier's transaction. Idempotent: a row already SETTLED
     * (or FAILED — the operator's terminal verdict) is left untouched, so the
     * backstop after an atomic settlement mark is a no-op read.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void markSettled(String provider, String eventId) {
        repository.findByProviderAndEventId(provider, eventId)
                .filter(event -> event.getProcessingState() == WebhookProcessingState.RECEIVED)
                .ifPresent(event -> {
                    event.markSettled();
                    repository.save(event);
                    log.debug("Webhook inbox row {} ({}) settled with its dispatch", eventId, provider);
                });
    }

    /**
     * Terminal, inspectable failure — written only by the recovery sweep when
     * a re-delivery can never apply legally (the intent moved past the event's
     * transition by another path). Runs in its own transaction: the failed
     * re-delivery's carrier transaction has already rolled back at this point.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String provider, String eventId, String reason) {
        repository.findByProviderAndEventId(provider, eventId)
                .filter(event -> event.getProcessingState() == WebhookProcessingState.RECEIVED)
                .ifPresent(event -> {
                    event.markFailed(reason);
                    repository.save(event);
                    log.error("Webhook inbox row {} ({}) marked FAILED: {} — operator signal:"
                            + " inspect the row (and its payload), then either accept the outcome"
                            + " or delete the payment_webhook_events row to re-arm the event",
                            eventId, provider, reason);
                });
    }

    /**
     * Compensating delete for a dispatch that failed after the row committed.
     * Provider-scoped like the gate it compensates (B5). Runs in its own
     * transaction: the caller's transaction is already rollback-only at this
     * point.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String provider, String eventId) {
        repository.deleteByProviderAndEventId(provider, eventId);
        log.info("Removed webhook event {} ({}) after a failed dispatch — the provider retry re-processes it",
                eventId, provider);
    }
}
