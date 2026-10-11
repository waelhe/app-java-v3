package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 6 (ADR-0002) — the order intent's money gate, unit-tested without
 * the PSP (the {@code PaymentsServiceTest} construction verbatim): the
 * deterministic idempotency key replays the SAME intent, a foreign payer
 * is refused, a zero amount is refused before any write, and the
 * cancellation's money half walks the intent's state machine exactly once
 * (the events prove it).
 */
class OrderIntentContractTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final com.marketplace.shared.security.CurrentUserProvider currentUserProvider =
            mock(com.marketplace.shared.security.CurrentUserProvider.class);
    private final com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider =
            mock(com.marketplace.shared.api.BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> pspChannel = mock(ObjectProvider.class);
    private final Authentication authentication = mock(Authentication.class);
    private final WebhookEventRecorder webhookEventRecorder = new WebhookEventRecorder(webhookEventRepository);
    private final PaymentIntentSettlementService settlementService = new PaymentIntentSettlementService(
            intentRepository, paymentRepository, eventPublisher, webhookEventRecorder);
    private final PaymentsService service = new PaymentsService(
            intentRepository, paymentRepository, webhookEventRepository, eventPublisher,
            currentUserProvider, bookingParticipantProvider, webhookSecurity,
            webhookEventRecorder, settlementService, pspChannel);

    @Test
    void createOrderIntent_savesTheOrderOriginIntentWithTheDeterministicKey() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.empty());

        PaymentIntent intent = service.createOrderIntent(orderId, buyer, 17400L, "SAR");

        assertThat(intent.getOrderId()).isEqualTo(orderId);
        assertThat(intent.getOrigin()).isEqualTo("ORDER");
        assertThat(intent.getConsumerId()).isEqualTo(buyer);
        assertThat(intent.getIdempotencyKey()).isEqualTo("order-" + orderId);
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.CREATED);
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(intent.getId(), "INITIATED"));
    }

    @Test
    void createOrderIntent_replaysTheSameIntentForTheSameOrder() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        PaymentIntent existing = PaymentIntent.createForOrder(orderId, buyer, 17400L, "SAR", "order-" + orderId);
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.of(existing));

        PaymentIntent replayed = service.createOrderIntent(orderId, buyer, 17400L, "SAR");

        assertThat(replayed).isSameAs(existing);
        verify(intentRepository, never()).save(any());
    }

    @Test
    void createOrderIntent_refusesAForeignPayerReplay() {
        UUID orderId = UUID.randomUUID();
        PaymentIntent existing = PaymentIntent.createForOrder(orderId, UUID.randomUUID(),
                17400L, "SAR", "order-" + orderId);
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.createOrderIntent(orderId, UUID.randomUUID(), 17400L, "SAR"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void createOrderIntent_refusesAZeroAmountBeforeAnyWrite() {
        UUID orderId = UUID.randomUUID();
        when(intentRepository.findByIdempotencyKey("order-" + orderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrderIntent(orderId, UUID.randomUUID(), 0L, "SAR"))
                .isInstanceOf(ConflictException.class);
        verify(intentRepository, never()).save(any());
    }

    @Test
    void autoRefundByOrder_cancelsTheUnpaidIntent() {
        UUID orderId = UUID.randomUUID();
        PaymentIntent unpaid = PaymentIntent.createForOrder(orderId, UUID.randomUUID(),
                100L, "SAR", "order-" + orderId);
        when(intentRepository.findByOrderId(orderId)).thenReturn(Optional.of(unpaid));

        service.autoRefundByOrder(orderId);

        assertThat(unpaid.getStatus()).isEqualTo(PaymentIntentStatus.CANCELLED);
        verify(intentRepository).save(unpaid);
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(unpaid.getId(), "CANCELLED"));
    }

    @Test
    void autoRefundByOrder_refundsTheCollectedIntentInFull() {
        UUID orderId = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        PaymentIntent paid = PaymentIntent.createForOrder(orderId, buyer, 100L, "SAR", "order-" + orderId);
        paid.markSucceeded();
        // No local payment row (never charged through settlement) — the
        // intent-level terminal mark is the honest state, and the REFUNDED
        // event still fires for the ledger's mirror.
        when(intentRepository.findByOrderId(orderId)).thenReturn(Optional.of(paid));

        service.autoRefundByOrder(orderId);

        assertThat(paid.getStatus()).isEqualTo(PaymentIntentStatus.REFUNDED);
        assertThat(paid.getRefundedAmountCents()).isEqualTo(100L);
        verify(intentRepository).save(paid);
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(paid.getId(), "REFUNDED"));
    }
}
