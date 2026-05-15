package com.marketplace.payments;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.*;
import static org.instancio.Select.field;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentsServiceTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    private final Authentication authentication = mock(Authentication.class);
    private final PaymentsService service = new PaymentsService(
            intentRepository,
            paymentRepository,
            webhookEventRepository,
            eventPublisher,
            currentUserProvider,
            bookingParticipantProvider,
            webhookSecurity
    );

    @Test
    void createIntent_savesNewIntentUsingBookingPrice() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        String idempotencyKey = "key-123";
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();

        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = service.createIntent(bookingId, consumerId, idempotencyKey);

        assertEquals(PaymentIntentStatus.CREATED, intent.getStatus());
        assertEquals(bookingId, intent.getBookingId());
        assertEquals(5000L, intent.getAmountCents());
    }

    @Test
    void createIntent_idempotencyReturnsExisting() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        String idempotencyKey = "key-123";
        PaymentIntent existing = of(PaymentIntent.class)
                .set(field(PaymentIntent::getBookingId), bookingId)
                .set(field(PaymentIntent::getConsumerId), consumerId)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getIdempotencyKey), idempotencyKey)
                .create();

        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentIntent result = service.createIntent(bookingId, consumerId, idempotencyKey);

        assertEquals(existing.getId(), result.getId());
        verify(intentRepository, never()).save(any());
        verifyNoInteractions(bookingParticipantProvider);
    }

    @Test
    void createIntent_rejectsWhenUserIsNotBookingParticipant() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(AccessDeniedException.class, () -> service.createIntent(bookingId, consumerId, null));
    }

    @Test
    void createIntent_rejectsWhenBookingNotConfirmed() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "PENDING")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        assertThrows(IllegalStateException.class, () -> service.createIntent(bookingId, consumerId, null));
    }

    @Test
    void createIntent_rejectsIdempotencyKeyBelongingToAnotherConsumer() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        UUID otherConsumerId = create(UUID.class);
        String idempotencyKey = "key-123";
        PaymentIntent existing = of(PaymentIntent.class)
                .set(field(PaymentIntent::getConsumerId), otherConsumerId)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getIdempotencyKey), idempotencyKey)
                .create();
        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));
        assertThrows(AccessDeniedException.class,
                () -> service.createIntent(bookingId, consumerId, idempotencyKey));
    }

    @Test
    void createIntent_publishesEvent() {
        UUID bookingId = create(UUID.class);
        UUID consumerId = create(UUID.class);
        BookingInfo bookingInfo = of(BookingInfo.class)
                .set(field(BookingInfo::consumerId), consumerId)
                .set(field(BookingInfo::status), "CONFIRMED")
                .set(field(BookingInfo::priceCents), 5000L)
                .set(field(BookingInfo::currency), "SAR")
                .create();
        when(intentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        service.createIntent(bookingId, consumerId, "key-123");
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.PaymentStateChangedEvent.class));
    }

    @Test
    void processIntent_propagatesCircuitBreakerOpenWithoutFallback() {
        UUID id = create(UUID.class);
        CallNotPermittedException circuitOpen = CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("paymentProcessing")
        );
        when(intentRepository.findById(id)).thenThrow(circuitOpen);

        assertThrows(CallNotPermittedException.class, () -> service.processIntent(id, authentication));
    }

    @Test
    void processIntent_success() {
        UUID id = create(UUID.class);
        UUID consumerId = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getConsumerId), consumerId)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent result = service.processIntent(id, authentication);

        assertEquals(PaymentIntentStatus.PROCESSING, result.getStatus());
    }

    @Test
    void cancelIntent_succeedsForCreatedIntent() {
        UUID id = create(UUID.class);
        UUID consumerId = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getConsumerId), consumerId)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        PaymentIntent cancelled = service.cancelIntent(id, authentication);

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void cancelIntent_throwsWhenNotOwner() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(create(UUID.class));
        assertThrows(AccessDeniedException.class, () -> service.cancelIntent(id, authentication));
    }

    @Test
    void cancelIntent_allowsAdmin() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        assertDoesNotThrow(() -> service.cancelIntent(id, authentication));
    }

    @Test
    void getIntent_throwsWhenNotFound() {
        UUID id = create(UUID.class);
        when(intentRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getIntent(id));
    }

    @Test
    void getIntent_returnsIntent() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        assertEquals(intent, service.getIntent(id));
    }

    @Test
    void getIntentForUser_returnsIntent() {
        UUID id = create(UUID.class);
        UUID consumerId = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getConsumerId), consumerId)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        assertEquals(intent, service.getIntentForUser(id, authentication));
    }

    @Test
    void getIntentForUser_throwsWhenNotOwner() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(create(UUID.class));
        assertThrows(AccessDeniedException.class, () -> service.getIntentForUser(id, authentication));
    }

    @Test
    void getIntentForUser_allowsAdmin() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        assertDoesNotThrow(() -> service.getIntentForUser(id, authentication));
    }

    @Test
    void getIntentSummary_returnsSummary() {
        UUID id = create(UUID.class);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        PaymentSummary summary = service.getIntentSummary(id);
        assertEquals(intent.getId(), summary.id());
    }

    @Test
    void listIntents_returnsAll() {
        var pageable = PageRequest.of(0, 10);
        when(intentRepository.findAll(pageable)).thenReturn(Page.empty());
        assertNotNull(service.listIntents(pageable));
    }

    @Test
    void listIntentsSummaries_returnsSummaries() {
        var pageable = PageRequest.of(0, 10);
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.CREATED)
                .create();
        when(intentRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(intent)));
        Page<PaymentSummary> result = service.listIntentsSummaries(pageable);
        assertEquals(1, result.getTotalElements());
    }

    @Test
    void confirmIntent_confirmsAndCompletesPayment() {
        UUID id = create(UUID.class);
        String externalId = "ext-123";
        PaymentIntent intent = of(PaymentIntent.class)
                .set(field(PaymentIntent::getAmountCents), 5000L)
                .set(field(PaymentIntent::getStatus), PaymentIntentStatus.PROCESSING)
                .create();
        Payment payment = of(Payment.class)
                .set(field(Payment::getPaymentIntentId), id)
                .set(field(Payment::getAmountCents), 5000L)
                .set(field(Payment::getStatus), PaymentStatus.PENDING)
                .create();
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(id)).thenReturn(Optional.of(payment));
        PaymentIntent result = service.confirmIntent(id, externalId);
        assertEquals(PaymentIntentStatus.SUCCEEDED, result.getStatus());
        verify(eventPublisher).publishEvent(any(com.marketplace.shared.api.PaymentStateChangedEvent.class));
    }

    @Test
    void refundPayment_refundsAndReturnsPayment() {
        UUID paymentId = create(UUID.class);
        Payment payment = of(Payment.class)
                .set(field(Payment::getAmountCents), 5000L)
                .set(field(Payment::getStatus), PaymentStatus.COMPLETED)
                .create();
        payment.markCompleted("ext-1");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        Payment refunded = service.refundPayment(paymentId);
        assertEquals(PaymentStatus.REFUNDED, refunded.getStatus());
    }

    @Test
    void refundPayment_throwsWhenNotFound() {
        UUID paymentId = create(UUID.class);
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.refundPayment(paymentId));
    }

    @Test
    void processWebhookEvent_newEvent_savesAndReturnsTrue() {
        when(webhookEventRepository.findByEventId("evt_1")).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        boolean created = service.processWebhookEvent("mock", "evt_1", "payment.succeeded", "sig");
        assertTrue(created);
        verify(webhookEventRepository).save(any(PaymentWebhookEvent.class));
    }
}
