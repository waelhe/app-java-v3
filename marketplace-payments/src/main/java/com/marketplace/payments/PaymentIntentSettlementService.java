package com.marketplace.payments;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Provider-verified settlement of a payment intent — the L19 closed loop's
 * domain half. Two entry surfaces call it, deliberately with DIFFERENT
 * authorization contracts:
 * <ul>
 *   <li>the admin command {@code PaymentsService.confirmIntent(...)} —
 *       {@code @PreAuthorize("hasRole('ADMIN')")} on the command shell;</li>
 *   <li>the verified webhook dispatch
 *       ({@code PaymentsService.dispatchWebhookEvent}) — provider
 *       signature is the authorization (D-009: the MAC carries the
 *       dispatch-relevant fields, including the confirm target).</li>
 * </ul>
 *
 * <p><b>Why this class exists (N2):</b> both methods previously lived on
 * {@code PaymentsService}, and the webhook path reached them through
 * <em>self-invocation</em> — documented Spring Framework AOP behavior
 * (Reference › AOP › Proxying Mechanisms): a target-method call bypasses
 * the proxy, so the annotations on {@code confirmIntent} were silently
 * skipped on that path. The {@code @PreAuthorize} read as a security
 * boundary while one path went around it — an architecture lie — and
 * {@code @Observed}/{@code @Retry} never fired for webhook settlements
 * (the observation on {@code failIntent} was dead outright: it had no
 * other caller). Extracting the domain transition into its own bean puts
 * EVERY call through a proxy: the role check now lives on the admin
 * surface only (where it is true), and observability/resilience apply to
 * both paths.
 *
 * <p><b>Reads are fresh, not cached</b> (the {@code refundPayment}
 * pattern): settlement is a mutation — it must transition the CURRENT
 * row, never a cached snapshot of an earlier state. The cache is evicted
 * after commit through {@link CacheInvalidationRequested}, exactly like
 * every other mutating path in the module.
 */
@Service
@Transactional
public class PaymentIntentSettlementService {

    private static final Logger log = LoggerFactory.getLogger(PaymentIntentSettlementService.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WebhookEventRecorder webhookEventRecorder;

    public PaymentIntentSettlementService(PaymentIntentRepository paymentIntentRepository,
                                          PaymentRepository paymentRepository,
                                          ApplicationEventPublisher eventPublisher,
                                          WebhookEventRecorder webhookEventRecorder) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
        this.webhookEventRecorder = webhookEventRecorder;
    }

    /**
     * Provider-confirmed success: the intent lands SUCCEEDED, its payment
     * row COMPLETED, and the domain events fire (ledger credit + booking
     * auto-confirm listen for COMPLETED; cache evicted after commit).
     *
     * <p><b>REQUIRES_NEW — the settlement owns its transaction (CodeRabbit
     * #431, adopted from the root 2026-09-28):</b> this method runs under
     * {@code @Retry(name = "paymentProcessing")}, and the carrier it is
     * called from is itself transactional on BOTH paths (the class-level
     * {@code @Transactional} on {@code PaymentsService}). Under the default
     * REQUIRED propagation a first attempt that throws a retryable exception
     * crosses this method's transaction boundary and marks the SHARED
     * carrier transaction rollback-only (Spring Framework Reference,
     * Declarative Transaction Management: a runtime exception thrown through
     * a participating REQUIRED scope marks the whole transaction
     * rollback-only — the successful retry then joins the same poisoned
     * transaction and commits nothing, surfacing
     * {@code UnexpectedRollbackException} at the carrier's commit). That
     * failure mode is worst on the webhook path: the commit failure fires
     * from the interceptor AFTER {@code handleVerifiedWebhook}'s body — the
     * compensating dedup delete (the catch inside the body) never runs, the
     * already-committed dedup row (its own REQUIRES_NEW in
     * {@link WebhookEventRecorder}) survives, and the provider's retry is
     * acknowledged as already-processed — the payment never settles.
     * {@code REQUIRES_NEW} breaks the poisoning at the root: each confirm
     * attempt is its own transaction, so a failed attempt rolls back only
     * itself, the carrier is never marked, and the retry can actually
     * commit. The admin carrier is safe to suspend: {@code confirmIntent}'s
     * transaction contains nothing but this call (the command shell
     * delegates directly), so the settlement's independence breaks no
     * atomicity. The webhook carrier is equally safe: it holds only the
     * dedup SELECT (no write locks for the classic REQUIRES_NEW
     * self-deadlock to bite on). And if the settlement's OWN commit fails,
     * the exception surfaces inside the dispatch body where the documented
     * compensating delete (dedup row removed, provider retry re-processes)
     * already runs.
     */
    @Observed(name = "payment.confirm")
    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntent confirm(UUID id, String externalId) {
        return confirm(id, externalId, null);
    }

    /**
     * The webhook-dispatch half of the closed loop, carrying its inbox row
     * (R10 — Wave 2): the settlement marks the row SETTLED INSIDE this
     * transaction, so "the inbox row settled" and "the money moved" commit
     * as one atomic fact. A crash between the recorder's REQUIRES_NEW commit
     * and this method's commit leaves the row RECEIVED — the recovery sweep
     * re-delivers it; a crash after the commit leaves both facts durable.
     * The admin surface keeps the two-argument form (no inbox row exists for
     * an admin command).
     */
    @Observed(name = "payment.confirm")
    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntent confirm(UUID id, String externalId, WebhookEventRef inboxRow) {
        PaymentIntent intent = requireIntent(id);
        intent.markSucceeded();
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(p -> p.markCompleted(externalId));
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "COMPLETED"));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        if (inboxRow != null) {
            // REQUIRED propagation joins THIS transaction — the atomicity
            // contract; the idempotent filter inside leaves a row the retry
            // attempt already marked untouched.
            webhookEventRecorder.markSettled(inboxRow.provider(), inboxRow.eventId());
        }
        return intent;
    }

    /**
     * The failure half of the closed payment loop: mirrors
     * {@link #confirm(UUID, String)} exactly (state machine, payment row,
     * event, cache invalidation) so a provider-confirmed failure lands the
     * same way a provider-confirmed success does. The ledger listener
     * ignores non-COMPLETED states, so nothing is ever credited for a
     * failed payment.
     */
    @Observed(name = "payment.fail")
    public PaymentIntent fail(UUID id) {
        return fail(id, null);
    }

    /**
     * The webhook-dispatch half carrying its inbox row (R10 — Wave 2): the
     * failure settlement marks the row SETTLED inside the transaction its
     * effects commit in (the class-level {@code @Transactional} scope this
     * method has always run under — REQUIRED, joining the dispatch carrier),
     * so a crash between the recorder's commit and this method's commit
     * leaves the row RECEIVED for the recovery sweep, and a completed
     * dispatch leaves both facts durable together.
     */
    @Observed(name = "payment.fail")
    public PaymentIntent fail(UUID id, WebhookEventRef inboxRow) {
        PaymentIntent intent = requireIntent(id);
        intent.markFailed();
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(Payment::markFailed);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "FAILED"));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        if (inboxRow != null) {
            webhookEventRecorder.markSettled(inboxRow.provider(), inboxRow.eventId());
        }
        log.info("Payment intent {} settled as FAILED by the provider", id);
        return intent;
    }

    private PaymentIntent requireIntent(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + id));
    }
}
