package com.marketplace.payments;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Instant;
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
    void processIntent_propagatesCircuitBreakerOpenWithoutFallback() {
        UUID id = create(UUID.class);
        CallNotPermittedException circuitOpen = CallNotPermittedException.createCallNotPermittedException(
                io.github.resilience4j.circuitbreaker.CircuitBreaker.ofDefaults("paymentProcessing")
        );
        when(intentRepository.findById(id)).thenThrow(circuitOpen);

        assertThrows(CallNotPermittedException.class, () -> service.processIntent(id, authentication));
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
    void getIntent_throwsWhenNotFound() {
        UUID id = create(UUID.class);
        when(intentRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getIntent(id));
    }
}
