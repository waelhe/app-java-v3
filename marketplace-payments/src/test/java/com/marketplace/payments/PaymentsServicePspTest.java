package com.marketplace.payments;

import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pins the channel-activation semantics of the PSP layer (roadmap B3):
 * <ul>
 *   <li>inert channel — processIntent keeps the byte-for-byte legacy behavior
 *       (no remote call, null clientSecret)</li>
 *   <li>bound channel — remote intent created with the deterministic
 *       idempotency key, psp_intent_id link assigned, clientSecret returned</li>
 *   <li>Stripe webhook — 503 SU-001 when unbound; verified dispatch with
 *       psp_intent_id fallback resolution when bound</li>
 *   <li>L19 closed loop — payment_intent.payment_failed flips intent+payment
 *       to FAILED; charge.refunded syncs the remote cumulative actual; the
 *       bound-channel refund derives one idempotency key per request state</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentsServicePspTest {

    private final PaymentIntentRepository intentRepository = mock(PaymentIntentRepository.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentWebhookEventRepository webhookEventRepository = mock(PaymentWebhookEventRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final com.marketplace.shared.api.BookingParticipantProvider bookingParticipantProvider =
            mock(com.marketplace.shared.api.BookingParticipantProvider.class);
    private final PaymentWebhookSecurity webhookSecurity = mock(PaymentWebhookSecurity.class);
    @Mock
    private PspChannel pspChannel;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> boundChannel = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> inertChannel = mock(ObjectProvider.class);
    private final Authentication authentication = mock(Authentication.class);

    private PaymentsService service(ObjectProvider<PspChannel> channel) {
        return new PaymentsService(intentRepository, paymentRepository, webhookEventRepository,
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity,
                new WebhookEventRecorder(webhookEventRepository), channel);
    }

    private PaymentIntent ownedIntent() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, "key-1");
        UUID consumerId = intent.getConsumerId();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(consumerId);
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        return intent;
    }

    @Test
    void processIntent_inertChannel_keepsLegacyBehavior() {
        PaymentIntent intent = ownedIntent();
        when(inertChannel.getIfAvailable()).thenReturn(null);

        PaymentsService.ProcessIntentResult result =
                service(inertChannel).processIntent(intent.getId(), authentication);

        assertEquals(PaymentIntentStatus.PROCESSING, result.intent().getStatus());
        assertNull(result.clientSecret(), "inert channel must not leak a client secret");
        assertNull(result.intent().getPspIntentId(), "inert channel must not link a PSP intent");
        verifyNoInteractions(pspChannel);
    }

    @Test
    void processIntent_boundChannel_createsRemoteIntentWithDeterministicKey() {
        PaymentIntent intent = ownedIntent();
        UUID intentId = intent.getId();
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteIntent(eq(intentId), eq(5000L), eq("SAR"),
                eq("marketplace-intent-" + intentId)))
                .thenReturn(new PspChannel.RemoteIntent("pi_remote_1", "pi_remote_1_secret"));

        PaymentsService.ProcessIntentResult result =
                service(boundChannel).processIntent(intentId, authentication);

        assertEquals(PaymentIntentStatus.PROCESSING, result.intent().getStatus());
        assertEquals("pi_remote_1", result.intent().getPspIntentId());
        assertEquals("pi_remote_1_secret", result.clientSecret());
        // Retry replay: same key derived from the SAME local intent id —
        // the official idempotency contract.
        verify(pspChannel).createRemoteIntent(eq(intentId), anyLong(), anyString(),
                eq("marketplace-intent-" + intentId));
    }

    @Test
    void handleStripeWebhook_unboundChannel_answers503() {
        when(inertChannel.getIfAvailable()).thenReturn(null);

        ServiceUnavailableException thrown = assertThrows(ServiceUnavailableException.class,
                () -> service(inertChannel).handleStripeWebhook("{}", "t=1,v1=x"));

        assertTrue(thrown.getMessage().contains("PAYMENTS_STRIPE_API_KEY"));
    }

    @Test
    void handleStripeWebhook_metadataResolution_dispatchesVerifiedEvent() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_9")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_9", "payment_intent.succeeded",
                        intentId, "pi_remote_9", null));
        intent.markProcessing();
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        verify(webhookEventRepository).saveAndFlush(argThat(ev -> "stripe".equals(ev.getProvider())
                && "evt_9".equals(ev.getEventId())));
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
    }

    @Test
    void handleStripeWebhook_pspLinkFallback_resolvesIntentWithoutMetadata() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_10")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_10", "payment_intent.succeeded",
                        null, "pi_remote_10", null));
        intent.assignPspIntentId("pi_remote_10");
        intent.markProcessing();
        when(intentRepository.findByPspIntentId("pi_remote_10")).thenReturn(Optional.of(intent));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
    }

    @Test
    void handleStripeWebhook_duplicateEvent_isIdempotent() {
        UUID intentId = UUID.randomUUID(); // metadata id — never dispatched on a duplicate
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_11", "payment_intent.succeeded",
                        intentId, "pi_11", null));
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_11")).thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertFalse(created, "a replayed Stripe notification must not re-dispatch");
        verify(webhookEventRepository, never()).saveAndFlush(any());
    }

    @Test
    void handleStripeWebhook_concurrentDuplicateInsert_answersAlreadyProcessed() {
        // CodeRabbit #241: two concurrent deliveries of the same event both
        // pass the provider-scoped findByProviderAndEventId lookup (B5). The
        // recorder's flushed insert is the serialization point — the loser
        // (unique (provider, event_id) violation crossing the recorder's
        // transactional boundary) is answered already-processed (false / HTTP
        // 200), never a 5xx, and never dispatches.
        UUID intentId = UUID.randomUUID();
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_12", "payment_intent.succeeded",
                        intentId, "pi_12", null));
        // Pre-check: absent; post-DIVE re-check: the winner's row exists.
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_12"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint"));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertFalse(created, "the losing concurrent delivery is the already-processed replay");
        verify(intentRepository, never()).findById(any());
    }

    @Test
    void handleStripeWebhook_unresolvedSucceededEvent_isRejectedBeforeRecording() {
        // CodeRabbit #241 (swallowed event): a payment_intent.succeeded event
        // whose intent cannot be resolved would previously be recorded and
        // acknowledged — then deduplicated forever while the intent was never
        // confirmed. It must be rejected BEFORE recording so Stripe retries
        // it (non-2xx), by which time the intent resolves via metadata or the
        // V33 psp_intent_id link.
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_13", "payment_intent.succeeded",
                        null, "pi_unlinked_13", null));
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_13")).thenReturn(Optional.empty());
        when(intentRepository.findByPspIntentId("pi_unlinked_13")).thenReturn(Optional.empty());

        com.marketplace.shared.api.ConflictException thrown = assertThrows(
                com.marketplace.shared.api.ConflictException.class,
                () -> service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig"));

        assertTrue(thrown.getMessage().contains("evt_13"));
        // Rejected BEFORE the dedup row exists — the retry is not blocked.
        verify(webhookEventRepository, never()).saveAndFlush(any());
        verify(webhookEventRepository, never()).deleteByProviderAndEventId(eq("stripe"), any());
    }

    @Test
    void assignPspIntentId_repeatedSameLink_isIdempotent_conflictRejected() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 100L, null);
        intent.assignPspIntentId("pi_a");
        intent.assignPspIntentId("pi_a");
        assertEquals("pi_a", intent.getPspIntentId());
        assertThrows(IllegalStateException.class, () -> intent.assignPspIntentId("pi_b"));
    }

    // ---- L19: the closed payment loop (roadmap §5 Layer 19) ----

    /** Acceptance 1: a provider-confirmed failure flips intent+payment to FAILED. */
    @Test
    void handleStripeWebhook_paymentFailed_flipsIntentAndPaymentToFailed() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_14")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        Payment payment = Payment.create(intentId, 5000L);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_14", "payment_intent.payment_failed",
                        intentId, "pi_remote_14", null));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(PaymentIntentStatus.FAILED, intent.getStatus());
        assertEquals(PaymentStatus.FAILED, payment.getStatus());
        verify(eventPublisher).publishEvent(
                new com.marketplace.shared.api.PaymentStateChangedEvent(intentId, "FAILED"));
    }

    /** Acceptance 3 via the webhook net: local books follow the remote cumulative. */
    @Test
    void handleStripeWebhook_chargeRefunded_syncsRemoteCumulative() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_15")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("ch_15");
        // A dashboard partial refund of 300 — local state still zero.
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_15", "charge.refunded",
                        null, "pi_remote_15", new PspChannel.RefundSnapshot(300L)));
        when(intentRepository.findByPspIntentId("pi_remote_15")).thenReturn(Optional.of(intent));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
        assertEquals(300L, payment.getRefundedAmountCents());
        assertEquals(Long.valueOf(300L), intent.getRefundedAmountCents());
        verify(intentRepository).save(intent);
    }

    /** Same snapshot redelivered under a new event id: no-op, no state-machine violation. */
    @Test
    void handleStripeWebhook_chargeRefunded_alreadySynced_isANoOp() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_16")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        intent.markSucceeded();
        intent.markPartiallyRefunded(300L);
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("ch_16");
        payment.markPartiallyRefunded(300L);
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_16", "charge.refunded",
                        intentId, "pi_remote_16", new PspChannel.RefundSnapshot(300L)));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
        assertEquals(300L, payment.getRefundedAmountCents());
        verify(intentRepository, never()).save(any());
    }

    /** Acceptance 2: one remote refund call with the derived idempotency key. */
    @Test
    void refundPayment_boundChannelAndLinkedIntent_callsRemoteRefundOnceWithDerivedKey() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_r1");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_r1");
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(eq("pi_remote_r1"), eq(400L),
                eq("marketplace-refund-" + payment.getId() + "-0-400")))
                .thenReturn(new PspChannel.RemoteRefund("re_r1", "succeeded", 400L));

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L);

        verify(pspChannel, times(1)).createRemoteRefund(eq("pi_remote_r1"), eq(400L), anyString());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(400L, result.getRefundedAmountCents());
        assertEquals(Long.valueOf(400L), intent.getRefundedAmountCents());
    }

    /** Acceptance 3: the remote cumulative wins over the local sum (divergence case). */
    @Test
    void refundPayment_partialRefundAppliesRemoteCumulative() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_r2");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_r2");
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        // Remote says 800 total (a dashboard refund of 400 existed) while the
        // local request only accounts for its own 400 — the books follow remote.
        when(pspChannel.createRemoteRefund(eq("pi_remote_r2"), any(), anyString()))
                .thenReturn(new PspChannel.RemoteRefund("re_r2", "succeeded", 800L));

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L);

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(800L, result.getRefundedAmountCents());
        assertEquals(Long.valueOf(800L), intent.getRefundedAmountCents());
    }

    /** Acceptance 4 mirror: channel bound but intent unlinked — internal path, no remote call. */
    @Test
    void refundPayment_unlinkedIntent_keepsInternalPath() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_r3");
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L);

        verify(pspChannel, never()).createRemoteRefund(any(), any(), any());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(400L, result.getRefundedAmountCents());
    }

    /** Acceptance 2 + CodeRabbit round 1: a PENDING remote refund moves no money yet — books untouched. */
    @Test
    void refundPayment_pendingRemoteRefund_leavesLocalBooksUntouched() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_r4");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_r4");
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(any(), any(), anyString()))
                .thenReturn(new PspChannel.RemoteRefund("re_r4", "pending", 0L));

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L);

        assertEquals(PaymentStatus.COMPLETED, result.getStatus());
        assertEquals(0L, result.getRefundedAmountCents());
        verify(intentRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /** A non-succeeded/non-pending remote status is a loud conflict — no silent local advance. */
    @Test
    void refundPayment_failedRemoteRefundStatus_throwsConflict() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_r5");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_r5");
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(any(), any(), anyString()))
                .thenReturn(new PspChannel.RemoteRefund("re_r5", "failed", 0L));

        com.marketplace.shared.api.ConflictException thrown = assertThrows(
                com.marketplace.shared.api.ConflictException.class,
                () -> service(boundChannel).refundPayment(payment.getId(), 400L));

        assertTrue(thrown.getMessage().contains("failed"));
        verify(intentRepository, never()).save(any());
    }

    /** CodeRabbit round 1: out-of-order webhooks (no delivery-order guarantee) must not regress the books. */
    @Test
    void handleStripeWebhook_chargeRefunded_outOfOrderSnapshot_isIgnored() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_17")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        intent.markSucceeded();
        intent.markPartiallyRefunded(800L);
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("ch_17");
        payment.markPartiallyRefunded(800L);
        // A stale event generated before the 800-cent refund: its snapshot
        // (300) predates the synced total and must not pull the books back.
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_17", "charge.refunded",
                        intentId, "pi_remote_17", new PspChannel.RefundSnapshot(300L)));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        assertEquals(800L, payment.getRefundedAmountCents());
        assertEquals(Long.valueOf(800L), intent.getRefundedAmountCents());
        verify(intentRepository, never()).save(any());
    }

    /** The retry contract: same request state ⇒ same key; advanced state ⇒ new key. */
    @Test
    void refundIdempotencyKey_isDeterministicPerState() {
        UUID paymentId = UUID.randomUUID();
        assertEquals(
                PaymentsService.refundIdempotencyKey(paymentId, 0L, 400L),
                PaymentsService.refundIdempotencyKey(paymentId, 0L, 400L));
        assertEquals("marketplace-refund-" + paymentId + "-0-full",
                PaymentsService.refundIdempotencyKey(paymentId, 0L, null));
        // A rolled-back retry re-derives the same key (0 state); the NEXT
        // distinct refund (state advanced to 400) derives a different one.
        assertNotEquals(
                PaymentsService.refundIdempotencyKey(paymentId, 0L, 400L),
                PaymentsService.refundIdempotencyKey(paymentId, 400L, 400L));
    }
}
