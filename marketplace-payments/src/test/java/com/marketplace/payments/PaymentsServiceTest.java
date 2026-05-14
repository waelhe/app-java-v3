package com.marketplace.payments;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentsServiceTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    private final Authentication authentication = mock(Authentication.class);
    private PaymentsService service;
    private final UUID consumerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PaymentsService(
                intentRepository, paymentRepository, webhookEventRepository,
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity);
    }

    @Test
    void createIntent_savesNewIntentUsingBookingPrice() {
        UUID bookingId = UUID.randomUUID();
        String idempotencyKey = "key-123";
        BookingInfo bookingInfo = new BookingInfo(
                UUID.randomUUID(), consumerId, "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());

        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = service.createIntent(bookingId, consumerId, idempotencyKey);

        assertEquals(PaymentIntentStatus.CREATED, intent.getStatus());
        assertEquals(5000L, intent.getAmountCents());
    }

    @Test
    void createIntent_idempotencyReturnsExisting() {
        UUID bookingId = UUID.randomUUID();
        String idempotencyKey = "key-123";
        PaymentIntent existing = PaymentIntent.create(bookingId, consumerId, 5000L, idempotencyKey);
        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentIntent result = service.createIntent(bookingId, consumerId, idempotencyKey);

        assertEquals(existing.getId(), result.getId());
        verify(intentRepository, never()).save(any());
        verifyNoInteractions(bookingParticipantProvider);
    }

    @Test
    void createIntent_rejectsWhenUserIsNotBookingParticipant() {
        UUID bookingId = UUID.randomUUID();
        BookingInfo bookingInfo = new BookingInfo(
                UUID.randomUUID(), UUID.randomUUID(), "CONFIRMED", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(AccessDeniedException.class, () -> service.createIntent(bookingId, consumerId, null));
    }

    @Test
    void createIntent_rejectsWhenBookingNotConfirmed() {
        UUID bookingId = UUID.randomUUID();
        BookingInfo bookingInfo = new BookingInfo(
                UUID.randomUUID(), consumerId, "PENDING", 5000L, "SAR", Instant.now(), Instant.now());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(IllegalStateException.class, () -> service.createIntent(bookingId, consumerId, null));
    }

    @Test
    void createIntent_rejectsIdempotencyKeyFromAnotherConsumer() {
        var bookingId = UUID.randomUUID();
        var idempotencyKey = "key-123";
        var existing = PaymentIntent.create(bookingId, UUID.randomUUID(), 5000L, idempotencyKey);
        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        assertThrows(AccessDeniedException.class,
                () -> service.createIntent(bookingId, consumerId, idempotencyKey));
    }

    @Test
    void processIntent_propagatesCircuitBreakerOpenWithoutFallback() {
        UUID id = UUID.randomUUID();
        CallNotPermittedException circuitOpen = CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("paymentProcessing"));
        when(intentRepository.findById(id)).thenThrow(circuitOpen);

        assertThrows(CallNotPermittedException.class, () -> service.processIntent(id, authentication));
    }

    @Test
    void processIntent_marksProcessingAndCreatesPayment() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        PaymentIntent result = service.processIntent(id, authentication);

        assertEquals(PaymentIntentStatus.PROCESSING, result.getStatus());
        verify(paymentRepository).save(any(Payment.class));
    }

    @Test
    void cancelIntent_succeedsForCreatedIntent() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        PaymentIntent cancelled = service.cancelIntent(id, authentication);

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void cancelIntent_throwsWhenNotOwner() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());

        assertThrows(AccessDeniedException.class, () -> service.cancelIntent(id, authentication));
    }

    @Test
    void getIntentForUser_returnsIntentForConsumer() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        PaymentIntent result = service.getIntentForUser(id, authentication);

        assertEquals(intent.getId(), result.getId());
    }

    @Test
    void getIntentForUser_throwsWhenNotOwner() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());

        assertThrows(AccessDeniedException.class, () -> service.getIntentForUser(id, authentication));
    }

    @Test
    void getIntentForUser_allowsAdmin() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);

        PaymentIntent result = service.getIntentForUser(id, authentication);

        assertEquals(intent.getId(), result.getId());
    }

    @Test
    void confirmIntent_marksSucceeded() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        Payment payment = Payment.create(intent.getId(), intent.getAmountCents());
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(id)).thenReturn(Optional.of(payment));

        PaymentIntent result = service.confirmIntent(id, "ext-123");

        assertEquals(PaymentIntentStatus.SUCCEEDED, result.getStatus());
        verify(eventPublisher).publishEvent(any(Object.class));
    }

    @Test
    void refundPayment_marksRefunded() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.create(UUID.randomUUID(), 5000L);
        payment.markCompleted("ext-1");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

        Payment result = service.refundPayment(paymentId);

        assertEquals(PaymentStatus.REFUNDED, result.getStatus());
    }

    @Test
    void refundPayment_throwsWhenNotFound() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.refundPayment(paymentId));
    }

    @Test
    void processWebhookEvent_createsNewEvent() {
        when(webhookEventRepository.findByEventId("evt_1")).thenReturn(Optional.empty());

        boolean created = service.processWebhookEvent("stripe", "evt_1", "payment.succeeded", "sig");

        assertTrue(created);
        verify(webhookEventRepository).save(any(PaymentWebhookEvent.class));
    }

    @Test
    void processWebhookEvent_ignoresDuplicate() {
        when(webhookEventRepository.findByEventId("evt_1")).thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));

        boolean created = service.processWebhookEvent("stripe", "evt_1", "payment.succeeded", "sig");

        assertFalse(created);
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void getIntent_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(intentRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getIntent(id));
    }

    @Test
    void getIntent_returnsIntent() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));

        PaymentIntent result = service.getIntent(id);

        assertEquals(intent.getId(), result.getId());
    }

    @Test
    void listIntents_returnsAll() {
        var pageable = PageRequest.of(0, 10);
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        when(intentRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(intent)));

        Page<PaymentIntent> result = service.listIntents(pageable);

        assertEquals(1, result.getContent().size());
    }

    @Test
    void listIntentsSummaries_mapsToSummaries() {
        var pageable = PageRequest.of(0, 10);
        var intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        when(intentRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(intent)));

        var result = service.listIntentsSummaries(pageable);

        assertEquals(1, result.getContent().size());
        assertEquals(intent.getId(), result.getContent().getFirst().id());
    }

    @Test
    void getIntentSummary_returnsSummary() {
        UUID id = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));

        var result = service.getIntentSummary(id);

        assertEquals(intent.getId(), result.id());
    }
}
