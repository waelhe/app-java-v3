package com.marketplace.payments;

import com.marketplace.shared.api.CacheInvalidationRequested;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 15 (agent findings 2026-10-26T11:43 export) — the abandonment race's
 * full protocol, measured on the real settlement and the real dispatch (the
 * {@link PaymentsServicePspTest} wiring): a domain cancellation abandons an
 * in-flight payment (CANCELLATION_PENDING — never a terminal failure), and
 * the PROVIDER's own truth resolves the race both ways:
 * <ol>
 *   <li><b>The capture WINS:</b> {@code payment_intent.succeeded} settles the
 *       books (SUCCEEDED + payment COMPLETED) and publishes
 *       {@link PaymentAbandonmentCapturedEvent} INSIDE the settlement
 *       transaction — the durable registry owns the recovery, and
 *       {@code refundAbandonedCapture} pays the buyer's money back through
 *       the ONE refund contract under the abandonment-scoped STABLE
 *       idempotency key (every resubmission replays the same remote
 *       refund).</li>
 *   <li><b>The provider itself cancels/expires:</b> {@code
 *       payment_intent.canceled} / {@code payment_intent.expired} resolves
 *       the abandonment to FAILED (no money ever moved); every other state
 *       is the idempotent no-op read.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AbandonmentCaptureRecoveryTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final org.springframework.context.ApplicationEventPublisher eventPublisher =
            mock(org.springframework.context.ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider =
            mock(com.marketplace.shared.api.BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    @Mock
    private PspChannel pspChannel;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> boundChannel = mock(ObjectProvider.class);

    private PaymentsService service() {
        WebhookEventRecorder recorder = new WebhookEventRecorder(webhookEventRepository);
        return new PaymentsService(intentRepository, paymentRepository, webhookEventRepository,
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity,
                recorder,
                new PaymentIntentSettlementService(intentRepository, paymentRepository, eventPublisher, recorder),
                new SettlementEligibilityService(mock(com.marketplace.shared.api.LedgerReadPort.class),
                        intentRepository, bookingParticipantProvider),
                boundChannel, new CommissionProperties(java.math.BigDecimal.valueOf(0.10)));
    }

    /** PROCESSING → the abandonment record (failInFlight's new law). */
    private PaymentIntent abandonedIntent() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(),
                5000L, "SAR", "abandon-key", null);
        intent.markProcessing();
        intent.markCancellationPending();
        intent.assignPspIntentId("pi_abandoned");
        return intent;
    }

    @Test
    void theCaptureThatWinsTheRace_settlesTheBooksAndDispatchesTheDurableRecovery() {
        PaymentIntent intent = abandonedIntent();
        UUID intentId = intent.getId();
        Payment payment = Payment.create(intentId, 5000L);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_win")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_win", "payment_intent.succeeded",
                        intentId, "pi_abandoned", null));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);

        boolean created = service().handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        // the books settled to the provider truth — the pre-fix answered the
        // machine's terminal guard with ConflictException forever here
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        // the abandonment's own decision rides the durable captured event,
        // published INSIDE the settlement transaction (registry-recorded)
        verify(eventPublisher).publishEvent(new PaymentAbandonmentCapturedEvent(intentId));
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(intentId, "COMPLETED"));
    }

    @Test
    void theOrganicSuccess_neverDispatchesTheAbandonmentRecovery() {
        // a plain PROCESSING intent (no abandonment) settles the same way and
        // publishes NO recovery event — the abandonment branch is exclusive
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(),
                5000L, "SAR", "plain-key", null);
        intent.markProcessing();
        UUID intentId = intent.getId();
        Payment payment = Payment.create(intentId, 5000L);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_plain")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_plain", "payment_intent.succeeded",
                        intentId, "pi_plain", null));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);

        assertTrue(service().handleStripeWebhook("payload", "t=1,v1=sig"));

        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
        verify(eventPublisher, never()).publishEvent(any(PaymentAbandonmentCapturedEvent.class));
    }

    @Test
    void theAbandonmentRefund_paysTheFullCaptureOnceUnderTheStableOperationKey() {
        PaymentIntent intent = abandonedIntent();
        intent.markSucceeded(); // the capture won; the settlement already ran
        UUID intentId = intent.getId();
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("ch_abandoned");
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(eq("pi_abandoned"), eq(null),
                eq("marketplace-refund-" + payment.getId() + "-abandonment-" + intentId)))
                .thenReturn(new PspChannel.RemoteRefund("re_abandoned", "succeeded", 5000L));

        service().refundAbandonedCapture(intentId);

        // ONE remote refund for the whole captured amount, under the
        // abandonment-scoped key — every registry resubmission re-derives
        // THIS key and replays the same refund, never a second payment
        verify(pspChannel, times(1)).createRemoteRefund(eq("pi_abandoned"), eq(null),
                eq("marketplace-refund-" + payment.getId() + "-abandonment-" + intentId));
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(PaymentIntentStatus.REFUNDED, intent.getStatus());
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(intentId, "REFUNDED"));
    }

    @Test
    void theAbandonmentRefund_resubmissionAfterTheRefundExecuted_isTheNoOpRead() {
        PaymentIntent intent = abandonedIntent();
        intent.markSucceeded();
        intent.markRefunded(); // the recovery already paid the buyer back
        UUID intentId = intent.getId();
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);

        service().refundAbandonedCapture(intentId);

        verify(pspChannel, never()).createRemoteRefund(anyString(), any(), anyString());
        verify(paymentRepository, never()).findByPaymentIntentId(any());
        verify(eventPublisher, never()).publishEvent(any(PaymentStateChangedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(CacheInvalidationRequested.class));
    }

    @Test
    void theProviderOwnCancellation_resolvesTheOpenAbandonmentToFailed() {
        PaymentIntent intent = abandonedIntent();
        UUID intentId = intent.getId();
        Payment payment = Payment.create(intentId, 5000L); // PENDING — the race was open
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_cancel")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_cancel", "payment_intent.canceled",
                        intentId, "pi_abandoned", null));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);

        boolean created = service().handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(PaymentIntentStatus.FAILED, intent.getStatus());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        verify(eventPublisher).publishEvent(new PaymentStateChangedEvent(intentId, "FAILED"));
    }

    @Test
    void theProviderExpiry_onANonAbandonmentState_isTheIdempotentNoOpRead() {
        // a plain PROCESSING intent (the pre-fix dispatch ignored the event
        // type entirely): the verdict keeps its state, the row settles, no
        // exception — the resolution only ever closes an OPEN abandonment
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(),
                5000L, "SAR", "live-key", null);
        intent.markProcessing();
        UUID intentId = intent.getId();
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_expire")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_expire", "payment_intent.expired",
                        intentId, "pi_live", null));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);

        boolean created = service().handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(PaymentIntentStatus.PROCESSING, intent.getStatus());
        verify(eventPublisher, never()).publishEvent(any(PaymentStateChangedEvent.class));
        verify(paymentRepository, never()).findByPaymentIntentId(any());
    }

    @Test
    void theAbandonmentKey_isStableForTheSameOperationOnly() {
        // pinned pure (no channel): the same abandoned intent re-derives the
        // same key regardless of refunded-so-far state; a different intent
        // derives a different one — the official idempotency contract,
        // scoped to the abandonment operation
        UUID paymentId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        assertEquals(
                PaymentsService.abandonmentScopedRefundIdempotencyKey(paymentId, intentId),
                PaymentsService.abandonmentScopedRefundIdempotencyKey(paymentId, intentId));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                PaymentsService.abandonmentScopedRefundIdempotencyKey(paymentId, intentId),
                PaymentsService.abandonmentScopedRefundIdempotencyKey(paymentId, UUID.randomUUID()));
        org.junit.jupiter.api.Assertions.assertNotEquals(
                PaymentsService.abandonmentScopedRefundIdempotencyKey(paymentId, intentId),
                PaymentsService.disputeScopedRefundIdempotencyKey(paymentId, intentId));
    }
}
