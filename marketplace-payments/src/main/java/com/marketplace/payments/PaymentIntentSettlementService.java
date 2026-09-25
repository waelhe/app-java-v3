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

    public PaymentIntentSettlementService(PaymentIntentRepository paymentIntentRepository,
                                          PaymentRepository paymentRepository,
                                          ApplicationEventPublisher eventPublisher) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Provider-confirmed success: the intent lands SUCCEEDED, its payment
     * row COMPLETED, and the domain events fire (ledger credit + booking
     * auto-confirm listen for COMPLETED; cache evicted after commit).
     */
    @Observed(name = "payment.confirm")
    @Retry(name = "paymentProcessing")
    public PaymentIntent confirm(UUID id, String externalId) {
        PaymentIntent intent = requireIntent(id);
        intent.markSucceeded();
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(p -> p.markCompleted(externalId));
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "COMPLETED"));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
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
        PaymentIntent intent = requireIntent(id);
        intent.markFailed();
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(Payment::markFailed);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "FAILED"));
        eventPublisher.publishEvent(new CacheInvalidationRequested(Set.of("paymentIntents"), id));
        log.info("Payment intent {} settled as FAILED by the provider", id);
        return intent;
    }

    private PaymentIntent requireIntent(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + id));
    }
}
