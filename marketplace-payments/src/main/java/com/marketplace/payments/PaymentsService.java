package com.marketplace.payments;

import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class PaymentsService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentsService(PaymentIntentRepository paymentIntentRepository,
                           PaymentRepository paymentRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public PaymentIntent getIntent(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<PaymentIntent> listIntents(Pageable pageable) {
        return paymentIntentRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<PaymentSummary> listIntentsSummaries(Pageable pageable) {
        return listIntents(pageable).map(this::toPaymentSummary);
    }

    @Transactional(readOnly = true)
    public PaymentSummary getIntentSummary(UUID id) {
        return toPaymentSummary(getIntent(id));
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent createIntent(UUID bookingId, UUID consumerId,
                                      Long amountCents, String idempotencyKey) {
        // Idempotency: return existing intent if same key
        if (idempotencyKey != null) {
            var existing = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        PaymentIntent intent = PaymentIntent.create(bookingId, consumerId, amountCents, idempotencyKey);
        PaymentIntent saved = paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(saved.getId(), "INITIATED"));
        return saved;
    }

    @PreAuthorize("hasRole('CONSUMER')")
    @Retry(name = "paymentProcessing")
    @CircuitBreaker(name = "paymentProcessing", fallbackMethod = "processIntentFallback")
    public PaymentIntent processIntent(UUID id) {
        PaymentIntent intent = getIntent(id);
        intent.markProcessing();
        // In production: integrate with payment gateway here
        Payment payment = Payment.create(intent.getId(), intent.getAmountCents());
        paymentRepository.save(payment);
        return intent;
    }

    /**
     * Fallback for processIntent when the circuit breaker is open.
     * Returns the intent in its current state without processing.
     * Called by Resilience4j via reflection at runtime.
     */
    @SuppressWarnings("unused")
    private PaymentIntent processIntentFallback(UUID id, Throwable throwable) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public PaymentIntent confirmIntent(UUID id, String externalId) {
        PaymentIntent intent = getIntent(id);
        intent.markSucceeded();
        // Mark the payment as completed
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(p -> p.markCompleted(externalId));
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "COMPLETED"));
        return intent;
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent cancelIntent(UUID id) {
        PaymentIntent intent = getIntent(id);
        intent.cancel();
        return intent;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public Payment refundPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        payment.markRefunded();
        return payment;
    }

    private PaymentSummary toPaymentSummary(PaymentIntent paymentIntent) {
        return new PaymentSummary(
                paymentIntent.getId(),
                paymentIntent.getBookingId(),
                paymentIntent.getConsumerId(),
                paymentIntent.getAmountCents(),
                paymentIntent.getCurrency(),
                paymentIntent.getStatus().name(),
                paymentIntent.getCreatedAt(),
                paymentIntent.getUpdatedAt()
        );
    }
}
