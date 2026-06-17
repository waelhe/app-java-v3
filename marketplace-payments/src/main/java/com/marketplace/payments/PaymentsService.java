package com.marketplace.payments;

import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

import io.micrometer.observation.annotation.Observed;

import java.util.UUID;

@Service
@Transactional
@Validated
public class PaymentsService implements PaymentsSpi {

    private static final Logger log = LoggerFactory.getLogger(PaymentsService.class);

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final PaymentWebhookSecurity paymentWebhookSecurity;

    public PaymentsService(PaymentIntentRepository paymentIntentRepository,
                           PaymentRepository paymentRepository,
                           PaymentWebhookEventRepository webhookEventRepository,
                           ApplicationEventPublisher eventPublisher,
                           CurrentUserProvider currentUserProvider,
                           BookingParticipantProvider bookingParticipantProvider,
                           PaymentWebhookSecurity paymentWebhookSecurity) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.eventPublisher = eventPublisher;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentWebhookSecurity = paymentWebhookSecurity;
    }

    public boolean processWebhookEvent(String provider, String eventId, String eventType, String signature) {
        return processWebhookEvent(provider, eventId, eventType, signature, null, null);
    }

    public boolean processWebhookEvent(String provider, String eventId, String eventType, String signature,
                                       UUID paymentIntentId, String externalId) {
        paymentWebhookSecurity.validateSignature(eventId + eventType, signature);
        if (webhookEventRepository.findByEventId(eventId).isPresent()) {
            return false;
        }
        webhookEventRepository.save(PaymentWebhookEvent.create(provider, eventId, eventType));
        dispatchWebhookEvent(eventType, paymentIntentId, externalId);
        return true;
    }

    private void dispatchWebhookEvent(String eventType, UUID paymentIntentId, String externalId) {
        switch (eventType) {
            case "payment_intent.succeeded" -> {
                if (paymentIntentId != null) {
                    log.info("Webhook dispatch: payment_intent.succeeded for intent {}", paymentIntentId);
                    confirmIntent(paymentIntentId, externalId);
                } else {
                    log.warn("Webhook payment_intent.succeeded missing paymentIntentId: eventType={}", eventType);
                }
            }
            case "payment_intent.processing" ->
                log.info("Webhook: payment intent processing confirmed by gateway: eventType={}", eventType);
            case "payment_intent.payment_failed" ->
                log.warn("Webhook: payment intent failed: eventType={}", eventType);
            default ->
                log.debug("Unhandled webhook event type: {}", eventType);
        }
    }

    @Transactional(readOnly = true)
    @Cacheable("paymentIntents")
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
        return paymentIntentRepository.findAllSummariesBy(pageable).map(this::toPaymentSummaryFromView);
    }

    private PaymentSummary toPaymentSummaryFromView(PaymentIntentSummaryView view) {
        return new PaymentSummary(
                view.getId(),
                view.getBookingId(),
                view.getConsumerId(),
                view.getAmountCents(),
                view.getCurrency(),
                view.getStatus().name(),
                view.getRefundedAmountCents(),
                view.getCreatedAt(),
                view.getUpdatedAt()
        );
    }

    @Transactional(readOnly = true)
    public PaymentSummary getIntentSummary(UUID id) {
        return toPaymentSummary(getIntent(id));
    }

    @Transactional(readOnly = true)
    public PaymentIntent getIntentForUser(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntent(id);
        verifyConsumerOwnership(intent, authentication);
        return intent;
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent createIntent(UUID bookingId, UUID consumerId, String idempotencyKey) {
        // Idempotency: return existing intent if same key
        if (idempotencyKey != null) {
            var existing = paymentIntentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                if (!existing.get().getConsumerId().equals(consumerId)) {
                    throw new AccessDeniedException("Idempotency key belongs to another consumer");
                }
                return existing.get();
            }
        }

        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(bookingId);
        bookingInfo.requireParticipant(consumerId);
        bookingInfo.requireStatus("CONFIRMED", "create payment intent");

        PaymentIntent intent = PaymentIntent.create(bookingId, consumerId, bookingInfo.priceCents(), idempotencyKey);
        PaymentIntent saved = paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(saved.getId(), "INITIATED"));
        return saved;
    }

    @Observed(name = "payment.process")
    @PreAuthorize("hasRole('CONSUMER')")
    @Retry(name = "paymentProcessing")
    @CircuitBreaker(name = "paymentProcessing")
    @ConcurrencyLimit(5)
    @CacheEvict(cacheNames = "paymentIntents", key = "#id")
    public PaymentIntent processIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
        intent.markProcessing();
        // In production: integrate with payment gateway here
        Payment payment = Payment.create(intent.getId(), intent.getAmountCents());
        paymentRepository.save(payment);
        return intent;
    }

    @Observed(name = "payment.confirm")
    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    @CacheEvict(cacheNames = "paymentIntents", key = "#id")
    public PaymentIntent confirmIntent(UUID id, String externalId) {
        PaymentIntent intent = getIntent(id);
        intent.markSucceeded();
        // Mark the payment as completed
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(p -> p.markCompleted(externalId));
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "COMPLETED"));
        return intent;
    }

    @Observed(name = "payment.cancel")
    @PreAuthorize("hasRole('CONSUMER')")
    @CacheEvict(cacheNames = "paymentIntents", key = "#id")
    public PaymentIntent cancelIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
        intent.cancel();
        return intent;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public Payment refundPayment(UUID paymentId) {
        return refundPayment(paymentId, null);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    @CacheEvict(cacheNames = "paymentIntents", key = "#result.paymentIntentId")
    public Payment refundPayment(UUID paymentId, @Min(1) Long amountCents) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
        PaymentIntent intent = paymentIntentRepository.findById(payment.getPaymentIntentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment intent not found: " + payment.getPaymentIntentId()));

        long alreadyRefunded = payment.getRefundedAmountCents();
        if (amountCents != null) {
            if (alreadyRefunded + amountCents > payment.getAmountCents()) {
                throw new ConflictException("Refund amount exceeds payment amount");
            }
            if (intent.getRefundedAmountCents() + amountCents > intent.getAmountCents()) {
                throw new ConflictException("Refund amount exceeds intent amount");
            }
        }
        boolean isFullRefund = (amountCents == null || alreadyRefunded + amountCents == payment.getAmountCents());
        if (isFullRefund) {
            payment.markRefunded();
            intent.markRefunded();
        } else {
            payment.markPartiallyRefunded(amountCents);
            intent.markPartiallyRefunded(amountCents);
        }
        paymentIntentRepository.save(intent);
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), intent.getStatus().name()));
        return payment;
    }

    @Retry(name = "paymentProcessing")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoRefundByBooking(UUID bookingId) {
        paymentIntentRepository.findByBookingId(bookingId).ifPresent(intent -> {
            intent.markRefunded();
            paymentIntentRepository.save(intent);
            paymentRepository.findByPaymentIntentId(intent.getId()).ifPresent(payment -> {
                payment.markRefunded();
            });
            eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "REFUNDED"));
        });
    }

    private PaymentSummary toPaymentSummary(PaymentIntent paymentIntent) {
        return new PaymentSummary(
                paymentIntent.getId(),
                paymentIntent.getBookingId(),
                paymentIntent.getConsumerId(),
                paymentIntent.getAmountCents(),
                paymentIntent.getCurrency(),
                paymentIntent.getStatus().name(),
                paymentIntent.getRefundedAmountCents(),
                paymentIntent.getCreatedAt(),
                paymentIntent.getUpdatedAt()
        );
    }

    private void verifyConsumerOwnership(PaymentIntent intent, Authentication authentication) {
        if (currentUserProvider.isAdmin(authentication)) {
            return;
        }
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!intent.getConsumerId().equals(currentUserId)) {
            throw new AccessDeniedException("You do not own this payment intent");
        }
    }
}
