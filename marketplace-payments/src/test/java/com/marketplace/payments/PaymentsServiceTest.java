package com.marketplace.payments;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentsServiceTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private final PaymentsService service = new PaymentsService(intentRepository, paymentRepository, eventPublisher, currentUserProvider);

    @Test
    void createIntent_savesNewIntent() {
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        String idempotencyKey = "key-123";

        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = service.createIntent(bookingId, consumerId, 5000L, idempotencyKey);

        assertEquals(PaymentIntentStatus.CREATED, intent.getStatus());
        assertEquals(bookingId, intent.getBookingId());
        assertEquals(5000L, intent.getAmountCents());
    }

    @Test
    void createIntent_idempotencyReturnsExisting() {
        UUID bookingId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        String idempotencyKey = "key-123";
        PaymentIntent existing = PaymentIntent.create(bookingId, consumerId, 5000L, idempotencyKey);

        when(intentRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

        PaymentIntent result = service.createIntent(bookingId, consumerId, 5000L, idempotencyKey);

        assertEquals(existing.getId(), result.getId());
        verify(intentRepository, never()).save(any());
    }

    @Test
    void cancelIntent_succeedsForCreatedIntent() {
        UUID id = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), consumerId, 5000L, null);
        when(intentRepository.findById(id)).thenReturn(Optional.of(intent));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);

        PaymentIntent cancelled = service.cancelIntent(id, authentication);

        assertEquals(PaymentIntentStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void getIntent_throwsWhenNotFound() {
        UUID id = UUID.randomUUID();
        when(intentRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.getIntent(id));
    }
}
