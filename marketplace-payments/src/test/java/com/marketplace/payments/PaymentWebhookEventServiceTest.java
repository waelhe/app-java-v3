package com.marketplace.payments;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentWebhookEventServiceTest {

    @Test
    void duplicateWebhookEventIsIgnored() {
        PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PaymentWebhookEventRepository webhookRepository = mock(PaymentWebhookEventRepository.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);

        PaymentsService service = new PaymentsService(intentRepository, paymentRepository, webhookRepository, publisher, currentUserProvider, bookingParticipantProvider);
        when(webhookRepository.findByEventId("evt_1")).thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));

        boolean created = service.processWebhookEvent("mock", "evt_1", "payment.succeeded");

        assertThat(created).isFalse();
        verify(webhookRepository, never()).save(any());
    }
}
