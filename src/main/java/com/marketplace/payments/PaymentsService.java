package com.marketplace.payments;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class PaymentsService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;

    public PaymentsService(PaymentIntentRepository paymentIntentRepository,
                           PaymentRepository paymentRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public PaymentIntent getIntent(UUID id) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + id));
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
        return paymentIntentRepository.save(intent);
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
    private PaymentIntent processIntentFallback(UUID id, Throwable throwable) {
        return paymentIntentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found: " + id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public PaymentIntent confirmIntent(UUID id, String externalId) {
        PaymentIntent intent = getIntent(id);
        intent.markSucceeded();
        // Mark the payment as completed
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(p -> p.markCompleted(externalId));
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
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
        payment.markRefunded();
        return payment;
    }
}
