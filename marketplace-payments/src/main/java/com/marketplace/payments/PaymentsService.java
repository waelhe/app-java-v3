package com.marketplace.payments;

import com.marketplace.payments.spi.PaymentsSpi;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class PaymentsService implements PaymentsSpi {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final PaymentGatewayClient paymentGatewayClient;
    private final PaymentWebhookEventRepository webhookEventRepository;

    public PaymentsService(PaymentIntentRepository paymentIntentRepository,
                           PaymentRepository paymentRepository,
                           ApplicationEventPublisher eventPublisher,
                           CurrentUserProvider currentUserProvider,
                           BookingParticipantProvider bookingParticipantProvider) {
        this(paymentIntentRepository, paymentRepository, eventPublisher, currentUserProvider, bookingParticipantProvider,
                intent -> new PaymentGatewayClient.GatewayPaymentResult(true, "mock_" + intent.getId(), "Accepted by mock gateway"),
                null);
    }

    public PaymentsService(PaymentIntentRepository paymentIntentRepository,
                           PaymentRepository paymentRepository,
                           ApplicationEventPublisher eventPublisher,
                           CurrentUserProvider currentUserProvider,
                           BookingParticipantProvider bookingParticipantProvider,
                           PaymentGatewayClient paymentGatewayClient,
                           PaymentWebhookEventRepository webhookEventRepository) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.eventPublisher = eventPublisher;
        this.currentUserProvider = currentUserProvider;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentGatewayClient = paymentGatewayClient;
        this.webhookEventRepository = webhookEventRepository;
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

    @PreAuthorize("hasRole('CONSUMER')")
    @Retry(name = "paymentProcessing")
    @CircuitBreaker(name = "paymentProcessing")
    @ConcurrencyLimit(5)
    public PaymentIntent processIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
        intent.markProcessing();
        PaymentGatewayClient.GatewayPaymentResult result = paymentGatewayClient.process(intent);
        if (!result.accepted()) {
            intent.markFailed();
            eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "FAILED"));
            return intent;
        }
        Payment payment = Payment.create(intent.getId(), intent.getAmountCents());
        paymentRepository.save(payment);
        return intent;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Retry(name = "paymentProcessing")
    public PaymentIntent confirmIntent(UUID id, String externalId) {
        PaymentIntent intent = getIntent(id);
        intent.markSucceeded();
        // Mark the payment as completed
        paymentRepository.findByPaymentIntentId(id)
                .ifPresent(p -> p.markCompleted(externalId));
        eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "SUCCEEDED"));
        return intent;
    }

    public PaymentIntent handleWebhook(String provider, String eventId, UUID paymentIntentId, String eventType, String payload) {
        if (webhookEventRepository == null) {
            throw new IllegalStateException("Webhook processing requires a webhook event repository");
        }
        if (webhookEventRepository.existsByProviderAndExternalEventId(provider, eventId)) {
            return getIntent(paymentIntentId);
        }
        webhookEventRepository.save(PaymentWebhookEvent.create(provider, eventId, paymentIntentId, eventType, payload));
        if ("payment.succeeded".equals(eventType)) {
            PaymentIntent intent = getIntent(paymentIntentId);
            if (intent.getStatus() == PaymentIntentStatus.PROCESSING) {
                return confirmIntent(paymentIntentId, eventId);
            }
            return intent;
        }
        if ("payment.failed".equals(eventType)) {
            PaymentIntent intent = getIntent(paymentIntentId);
            if (intent.getStatus() == PaymentIntentStatus.PROCESSING) {
                intent.markFailed();
                eventPublisher.publishEvent(new PaymentStateChangedEvent(intent.getId(), "FAILED"));
            }
            return intent;
        }
        return getIntent(paymentIntentId);
    }

    @PreAuthorize("hasRole('CONSUMER')")
    public PaymentIntent cancelIntent(UUID id, Authentication authentication) {
        PaymentIntent intent = getIntentForUser(id, authentication);
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
