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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    private final PaymentRefundOperationJournal refundOperationJournal =
            mock(PaymentRefundOperationJournal.class);
    private final Authentication authentication = mock(Authentication.class);

    private PaymentsService service(ObjectProvider<PspChannel> channel) {
        WebhookEventRecorder recorder = new WebhookEventRecorder(webhookEventRepository);
        return new PaymentsService(intentRepository, paymentRepository, webhookEventRepository,
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity,
                recorder,
                new PaymentIntentSettlementService(intentRepository, paymentRepository, eventPublisher, recorder),
                new SettlementEligibilityService(mock(com.marketplace.shared.api.LedgerReadPort.class), intentRepository, bookingParticipantProvider),
                channel, new CommissionProperties(java.math.BigDecimal.valueOf(0.10)), refundOperationJournal);
    }

    private PaymentIntent ownedIntent() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, "key-1", null);
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
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 100L, null, null, null);
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
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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

    /**
     * CodeRabbit round 5 on #828: the DISPATCH path carries the snapshot's
     * identities into the settle — through handleStripeWebhook itself, not a
     * direct syncRemoteRefund call. Four shapes: a keyed executed refund
     * settles its SUBMITTED operation (markExecuted with the identity); a
     * keyless refund (dashboard) never touches the journal lookup; a keyed
     * refund whose operation belongs to ANOTHER payment never settles; an
     * already-EXECUTED record delegates to the journal's documented
     * idempotence while the books still sync.
     */
    @Test
    void handleStripeWebhook_chargeRefunded_settlesTheIdentityMatchedOperation() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        for (String evt : new String[] {"evt_s1", "evt_s2", "evt_s3", "evt_s4"}) {
            when(webhookEventRepository.findByProviderAndEventId("stripe", evt)).thenReturn(Optional.empty());
        }

        // Case A: the keyed executed refund settles its SUBMITTED operation
        PaymentIntent intentA = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 2000L, null, null, null);
        intentA.markProcessing();
        intentA.markSucceeded();
        Payment paymentA = Payment.create(intentA.getId(), 2000L);
        paymentA.markCompleted("ch_s1");
        String keyA = "marketplace-refund-" + paymentA.getId() + "-return-rA-2000";
        PaymentRefundOperation frozenA = new PaymentRefundOperation(keyA, paymentA.getId(), 2000L, 0L);
        when(intentRepository.findById(intentA.getId())).thenReturn(Optional.of(intentA));
        when(paymentRepository.findByPaymentIntentId(intentA.getId())).thenReturn(Optional.of(paymentA));
        when(pspChannel.verifyWebhook("payloadA", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_s1", "charge.refunded",
                        intentA.getId(), "pi_remote_s1", new PspChannel.RefundSnapshot(2000L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_a", keyA)))));
        when(refundOperationJournal.findSubmitted(keyA)).thenReturn(frozenA);
        assertTrue(service(boundChannel).handleStripeWebhook("payloadA", "t=1,v1=sig"));
        assertEquals(PaymentStatus.REFUNDED, paymentA.getStatus());

        // Case B: a keyless (dashboard) refund — books sync, journal untouched
        PaymentIntent intentB = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        intentB.markProcessing();
        intentB.markSucceeded();
        Payment paymentB = Payment.create(intentB.getId(), 5000L);
        paymentB.markCompleted("ch_s2");
        when(intentRepository.findById(intentB.getId())).thenReturn(Optional.of(intentB));
        when(paymentRepository.findByPaymentIntentId(intentB.getId())).thenReturn(Optional.of(paymentB));
        when(pspChannel.verifyWebhook("payloadB", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_s2", "charge.refunded",
                        intentB.getId(), "pi_remote_s2", new PspChannel.RefundSnapshot(300L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_dash_b", null)))));
        assertTrue(service(boundChannel).handleStripeWebhook("payloadB", "t=1,v1=sig"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, paymentB.getStatus());

        // Case C: the keyed refund's operation belongs to ANOTHER payment —
        // never settled
        PaymentIntent intentC = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        intentC.markProcessing();
        intentC.markSucceeded();
        Payment paymentC = Payment.create(intentC.getId(), 5000L);
        paymentC.markCompleted("ch_s3");
        String keyC = "marketplace-refund-OTHER-payment-return-rC-2000";
        PaymentRefundOperation foreignOp = new PaymentRefundOperation(keyC, UUID.randomUUID(), 2000L, 0L);
        when(intentRepository.findById(intentC.getId())).thenReturn(Optional.of(intentC));
        when(paymentRepository.findByPaymentIntentId(intentC.getId())).thenReturn(Optional.of(paymentC));
        when(pspChannel.verifyWebhook("payloadC", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_s3", "charge.refunded",
                        intentC.getId(), "pi_remote_s3", new PspChannel.RefundSnapshot(300L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_c", keyC)))));
        when(refundOperationJournal.findSubmitted(keyC)).thenReturn(foreignOp);
        assertTrue(service(boundChannel).handleStripeWebhook("payloadC", "t=1,v1=sig"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, paymentC.getStatus());

        // Case D: the operation is already EXECUTED — the delegation rides
        // the journal's documented idempotence while the books still sync
        PaymentIntent intentD = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 2000L, null, null, null);
        intentD.markProcessing();
        intentD.markSucceeded();
        Payment paymentD = Payment.create(intentD.getId(), 2000L);
        paymentD.markCompleted("ch_s4");
        String keyD = "marketplace-refund-" + paymentD.getId() + "-return-rD-2000";
        PaymentRefundOperation executedD = new PaymentRefundOperation(keyD, paymentD.getId(), 2000L, 0L);
        executedD.markExecuted("re_d_prior");
        when(intentRepository.findById(intentD.getId())).thenReturn(Optional.of(intentD));
        when(paymentRepository.findByPaymentIntentId(intentD.getId())).thenReturn(Optional.of(paymentD));
        when(pspChannel.verifyWebhook("payloadD", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_s4", "charge.refunded",
                        intentD.getId(), "pi_remote_s4", new PspChannel.RefundSnapshot(2000L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_d", keyD)))));
        when(refundOperationJournal.findSubmitted(keyD)).thenReturn(executedD);
        assertTrue(service(boundChannel).handleStripeWebhook("payloadD", "t=1,v1=sig"));
        assertEquals(PaymentStatus.REFUNDED, paymentD.getStatus());
        // CodeRabbit round 7: an already-EXECUTED record is skipped at the
        // call site — no markExecuted delegation for it
        verify(refundOperationJournal, never()).markExecuted(eq(keyD), anyString());

        // Case E (CodeRabbit round 8): a STALE cumulative snapshot (1000
        // below the books' 1500) still carries a LIVE SUBMITTED identity —
        // the settle accepts the identity (the refund's completion is a fact
        // about the provider) while the out-of-order guard keeps the books
        // at their synced 1500: nothing saved, nothing published
        PaymentIntent intentE = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        intentE.markProcessing();
        intentE.markSucceeded();
        Payment paymentE = Payment.create(intentE.getId(), 5000L);
        paymentE.markCompleted("ch_s5");
        paymentE.markPartiallyRefunded(1500L);
        String keyE = "marketplace-refund-" + paymentE.getId() + "-return-rE-1000";
        PaymentRefundOperation frozenE = new PaymentRefundOperation(keyE, paymentE.getId(), 1000L, 500L);
        when(intentRepository.findById(intentE.getId())).thenReturn(Optional.of(intentE));
        when(paymentRepository.findByPaymentIntentId(intentE.getId())).thenReturn(Optional.of(paymentE));
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_s5")).thenReturn(Optional.empty());
        when(pspChannel.verifyWebhook("payloadE", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_s5", "charge.refunded",
                        intentE.getId(), "pi_remote_s5", new PspChannel.RefundSnapshot(1000L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_e", keyE)))));
        when(refundOperationJournal.findSubmitted(keyE)).thenReturn(frozenE);
        assertTrue(service(boundChannel).handleStripeWebhook("payloadE", "t=1,v1=sig"));
        verify(refundOperationJournal).markExecuted(eq(keyE), eq("re_e"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, paymentE.getStatus());
        assertEquals(1500L, paymentE.getRefundedAmountCents());

        // the settle contract: A delegates to the journal (the
        // identity-matched, payment-matched SUBMITTED operation); B never
        // looks the journal up; C's foreign operation and D's already-EXECUTED
        // record are never settled
        verify(refundOperationJournal).markExecuted(eq(keyA), eq("re_a"));
        verify(refundOperationJournal, never()).markExecuted(eq(keyC), anyString());
        // E joins the reads (A, C, D, E) and the settles (A, E); the four
        // books-sync saves are exactly A, B, C and D's — E's stale snapshot
        // saved nothing (the out-of-order guard skipped it)
        verify(refundOperationJournal, times(4)).findSubmitted(anyString());
        verify(intentRepository, times(4)).save(any());
    }

    /**
     * CodeRabbit round 6: a refund created PENDING succeeds asynchronously —
     * the refund.updated event (carrying the Refund object with the operation
     * key) settles the SUBMITTED operation. Round 10: when THIS dispatch
     * settles an operation and the channel is bound, the books sync to the
     * LIVE remote cumulative (fetched through the channel) before any
     * reconciliation reads them — the identity event can precede the
     * charge.refunded cumulative (Stripe does not guarantee delivery order).
     * The unbound-channel fallback keeps the round-6 no-op contract (below).
     */
    @Test
    void handleStripeWebhook_refundUpdated_settlesTheAsynchronousSuccess() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_ru1")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("ch_ru");
        String key = "marketplace-refund-" + payment.getId() + "-return-rU-2000";
        PaymentRefundOperation frozen = new PaymentRefundOperation(key, payment.getId(), 2000L, 0L);
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(pspChannel.verifyWebhook("payloadU", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_ru1", "refund.updated",
                        intentId, "pi_remote_ru", new PspChannel.RefundSnapshot(0L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_late", key)))));
        when(refundOperationJournal.findSubmitted(key)).thenReturn(frozen);
        // round 10: the live cumulative read the settle path fetches — the
        // provider's answer for this charge's refunded total after the late
        // success (2000 here).
        when(pspChannel.readRemoteRefundTotalCents("pi_remote_ru")).thenReturn(2000L);

        boolean created = service(boundChannel).handleStripeWebhook("payloadU", "t=1,v1=sig");

        assertTrue(created);
        // the identity settle ran — the journal's EXECUTED delegation
        verify(refundOperationJournal).markExecuted(eq(key), eq("re_late"));
        // round 10: the books SYNCED to the live remote cumulative before any
        // reconciliation could read the lagging local arithmetic — the intent
        // was saved and the state published like the charge.refunded vehicle
        verify(intentRepository).save(any());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
        assertEquals(2000L, payment.getRefundedAmountCents());
    }

    /**
     * Round 10 — the literal out-of-order sequence the inline finding proved:
     * a RETURN operation of 500 frozen at booksAtSubmit=0 answered PENDING; an
     * INDEPENDENT dashboard refund of 1000 landed first (charge.refunded,
     * books 1000); THEN refund.updated delivers this operation's success
     * identity. The settle must fetch the live remote cumulative (1500 — the
     * independent refund PLUS this operation's money) and sync the books to
     * it, so a re-completion answers the reconciled state against the TRUE
     * total (1500), never the lagging arithmetic (1000 >= 0 + 500).
     */
    @Test
    void handleStripeWebhook_refundUpdated_settlingAfterAnIndependentRefund_syncsTheBooksToTheLiveRemoteTotal() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_ru2")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 3000L, null, null, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intentId, 3000L);
        payment.markCompleted("ch_ru2");
        // the independent dashboard refund landed earlier: books already at 1000
        payment.markPartiallyRefunded(1000L);
        intent.markPartiallyRefunded(1000L);
        // the operation this identity event settles: 500 frozen at books 0
        String key = "marketplace-refund-" + payment.getId() + "-return-rU2-500";
        PaymentRefundOperation frozen = new PaymentRefundOperation(key, payment.getId(), 500L, 0L);
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(pspChannel.verifyWebhook("payloadU2", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_ru2", "refund.updated",
                        intentId, "pi_remote_ru2", new PspChannel.RefundSnapshot(0L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_late2", key)))));
        when(refundOperationJournal.findSubmitted(key)).thenReturn(frozen);
        // the live remote cumulative: the independent 1000 PLUS this
        // operation's 500 — the number the provider's own charge carries now
        when(pspChannel.readRemoteRefundTotalCents("pi_remote_ru2")).thenReturn(1500L);

        boolean created = service(boundChannel).handleStripeWebhook("payloadU2", "t=1,v1=sig");

        assertTrue(created);
        // the identity settle ran for THIS operation
        verify(refundOperationJournal).markExecuted(eq(key), eq("re_late2"));
        // the books now carry the TRUE total (1500), not the lagging 1000:
        // the re-completion's booksSynced gate reads 1500 >= 0 + 500 with the
        // operation's own money IN the books, and the status reflects the
        // still-partial refund of the 3000 charge
        assertEquals(1500L, payment.getRefundedAmountCents());
        assertEquals(1500L, intent.getRefundedAmountCents());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, payment.getStatus());
        verify(intentRepository).save(any());
    }

    /**
     * Round 10 — the null-read contract: the entry point itself requires the
     * channel (the webhook path's 503 SU-001 gate), so the reachable fallback
     * is a BOUND channel whose live cumulative read answers {@code null} —
     * the provider reports no charge yet for the intent (nothing refundable,
     * and this contract invents no arithmetic). The settle records the
     * identity (the journal's own REQUIRES_NEW) and the books keep awaiting
     * the charge.refunded vehicle.
     */
    @Test
    void handleStripeWebhook_refundUpdated_nullRemoteTotal_settlesIdentityWithoutTouchingTheBooks() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_ru3")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("ch_ru3");
        String key = "marketplace-refund-" + payment.getId() + "-return-rU3-2000";
        PaymentRefundOperation frozen = new PaymentRefundOperation(key, payment.getId(), 2000L, 0L);
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(pspChannel.verifyWebhook("payloadU3", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_ru3", "refund.updated",
                        intentId, "pi_remote_ru3", new PspChannel.RefundSnapshot(0L,
                        java.util.List.of(new PspChannel.ExecutedRefund("re_late3", key)))));
        when(refundOperationJournal.findSubmitted(key)).thenReturn(frozen);
        // the live read answers null: no charge-backed cumulative exists yet
        when(pspChannel.readRemoteRefundTotalCents("pi_remote_ru3")).thenReturn(null);

        boolean created = service(boundChannel).handleStripeWebhook("payloadU3", "t=1,v1=sig");

        assertTrue(created);
        verify(refundOperationJournal).markExecuted(eq(key), eq("re_late3"));
        // null remote total: books untouched, the charge.refunded vehicle owns
        // them — no save, no publish, no invented cumulative
        verify(intentRepository, never()).save(any());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(0L, payment.getRefundedAmountCents());
    }

    /** Same snapshot redelivered under a new event id: no-op, no state-machine violation. */
    @Test
    void handleStripeWebhook_chargeRefunded_alreadySynced_isANoOp() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_16")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L).payment();

        verify(pspChannel, times(1)).createRemoteRefund(eq("pi_remote_r1"), eq(400L), anyString());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(400L, result.getRefundedAmountCents());
        assertEquals(Long.valueOf(400L), intent.getRefundedAmountCents());
    }

    /**
     * The V230 return-scoped key (the RMA completion edge): a completing
     * return's refund derives the STABLE return-scoped PSP idempotency key —
     * payment + return + frozen amount, deliberately NOT the refunded-so-far
     * state — so a retried completion after a sibling return's refund advanced
     * the cumulative total re-derives the SAME key and replays the one remote
     * refund instead of double-charging (the review's Major finding).
     */
    @Test
    void refundPayment_returnScopedRefund_derivesTheStableReturnKey() {
        UUID returnId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_r3");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_r3");
        // a sibling return's refund already advanced the cumulative actual —
        // the state-derived key would have drifted past it; the return-scoped
        // key must not care
        payment.markPartiallyRefunded(1000L);
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(eq("pi_remote_r3"), eq(1500L),
                eq("marketplace-refund-" + payment.getId() + "-return-" + returnId + "-1500")))
                .thenReturn(new PspChannel.RemoteRefund("re_r3", "succeeded", 2500L));
        // CodeRabbit round 1 on #828: the RETURN scope now rides the journal
        // (the executed-proof the re-completion reads BEFORE any cap) — the
        // first submission freezes its record and the provider's answer is
        // marked EXECUTED, so the mock carries the save/find round-trip the
        // dispute-scoped tests already stub.
        when(refundOperationJournal.findSubmitted(anyString())).thenReturn(null);
        when(refundOperationJournal.ensureSubmitted(anyString(), any(), any(), anyLong()))
                .thenAnswer(inv -> new PaymentRefundOperation(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)));

        Payment result = service(boundChannel)
                .refundPayment(payment.getId(), 1500L,
                        PaymentsService.StableRefundScope.forReturn(returnId)).payment();

        // the remote cumulative actual (2500 = the sibling's 1000 + this 1500) wins
        verify(pspChannel, times(1)).createRemoteRefund(eq("pi_remote_r3"), eq(1500L),
                eq("marketplace-refund-" + payment.getId() + "-return-" + returnId + "-1500"));
        // CodeRabbit round 2 on #828 (nitpick): the journal calls' arguments —
        // the freeze carries the request's amount and the books' cumulative
        // baseline at submission (1000 = the sibling's advance), and the
        // provider's refund identity rides markExecuted under the same key.
        String journalKey = "marketplace-refund-" + payment.getId() + "-return-" + returnId + "-1500";
        verify(refundOperationJournal).ensureSubmitted(eq(journalKey), eq(payment.getId()),
                eq(1500L), eq(1000L));
        verify(refundOperationJournal).markExecuted(eq(journalKey), eq("re_r3"));
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(2500L, result.getRefundedAmountCents());
    }

    /**
     * CodeRabbit round 2 on #828 (major — data integrity): the RETURN scope's
     * rollback-then-webhook window. The remote refund executes at the PSP, the
     * carrier (the return completion) rolls back, and the charge.refunded
     * webhook then syncs the books — the operation row stays SUBMITTED (nothing
     * but the webhook's refund identity can settle it), so the pre-fix
     * re-completion rejected itself on the common cap forever (2000 synced +
     * 2000 frozen > 2000 total). The settle: the refund this service created
     * carries the operation key as its provider metadata, and the webhook —
     * the only witness of the provider's own refund record — settles the
     * journal by IDENTITY. The re-completion then reads EXECUTED and returns
     * the reconciled state, re-submitting nothing.
     */
    @Test
    void refundPayment_returnScopedRefund_pendingThenRollbackThenWebhookSync_recompletionSettles() {
        UUID returnId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 2000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_r9");
        Payment payment = Payment.create(intent.getId(), 2000L);
        payment.markCompleted("ch_r9");
        String key = "marketplace-refund-" + payment.getId() + "-return-" + returnId + "-2000";
        PaymentRefundOperation frozen = new PaymentRefundOperation(key, payment.getId(), 2000L, 0L);
        PaymentRefundOperation executed = new PaymentRefundOperation(key, payment.getId(), 2000L, 0L);
        executed.markExecuted("re_r9");
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        // the provider accepts the refund but answers pending — the books
        // await the webhook and the operation row stays SUBMITTED
        when(pspChannel.createRemoteRefund(eq("pi_remote_r9"), eq(2000L), eq(key)))
                .thenReturn(new PspChannel.RemoteRefund("re_r9", "pending", 0L));
        when(refundOperationJournal.findSubmitted(key))
                .thenReturn(null)      // round 1: the first submission
                .thenReturn(frozen)    // the webhook's identity settle probe
                .thenReturn(executed); // the re-completion reads EXECUTED
        when(refundOperationJournal.ensureSubmitted(eq(key), eq(payment.getId()), eq(2000L), eq(0L)))
                .thenReturn(frozen);

        PaymentsService svc = service(boundChannel);
        // round 1: pending at the provider — nothing refunded locally yet
        svc.refundPayment(payment.getId(), 2000L, PaymentsService.StableRefundScope.forReturn(returnId));
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        assertEquals(0L, payment.getRefundedAmountCents());

        // the carrier transaction rolls back (the operation row survives in
        // its own REQUIRES_NEW) and the charge.refunded webhook arrives: the
        // books sync to the remote cumulative AND the refund's identity —
        // carried as the operation key metadata — settles the journal
        svc.syncRemoteRefund(intent.getId(), 2000L,
                java.util.List.of(new PspChannel.ExecutedRefund("re_r9", key)));
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(2000L, payment.getRefundedAmountCents());
        verify(refundOperationJournal).markExecuted(eq(key), eq("re_r9"));

        // the re-completion reads the EXECUTED record, passes the books-sync
        // gate, and returns the reconciled state — WITHOUT re-submitting
        PaymentsService.RefundedPayment settled = svc.refundPayment(
                payment.getId(), 2000L, PaymentsService.StableRefundScope.forReturn(returnId));

        verify(pspChannel, times(1)).createRemoteRefund(eq("pi_remote_r9"), eq(2000L), eq(key));
        assertEquals(PaymentStatus.REFUNDED, settled.payment().getStatus());
        assertEquals(2000L, settled.payment().getRefundedAmountCents());
    }

    /**
     * The key's stability contract, pinned pure (no channel): the same
     * completing return re-derives the same key regardless of the
     * refunded-so-far state; a different return or amount derives a
     * different one; a null amount reads "full".
     */
    @Test
    void returnScopedRefundIdempotencyKey_isStableForTheSameReturnOnly() {
        UUID paymentId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        UUID otherReturnId = UUID.randomUUID();

        String first = PaymentsService.returnScopedRefundIdempotencyKey(paymentId, returnId, 1500L);
        String retried = PaymentsService.returnScopedRefundIdempotencyKey(paymentId, returnId, 1500L);
        String full = PaymentsService.returnScopedRefundIdempotencyKey(paymentId, returnId, null);
        String sibling = PaymentsService.returnScopedRefundIdempotencyKey(paymentId, otherReturnId, 1500L);
        String otherAmount = PaymentsService.returnScopedRefundIdempotencyKey(paymentId, returnId, 2500L);

        assertEquals(first, retried, "the same return must re-derive the same key");
        assertEquals("marketplace-refund-" + paymentId + "-return-" + returnId + "-1500", first);
        assertEquals("marketplace-refund-" + paymentId + "-return-" + returnId + "-full", full);
        assertNotEquals(first, sibling, "a sibling return's key differs");
        assertNotEquals(first, otherAmount, "a different frozen amount differs");
    }

    /**
     * Row 35 (agent findings 2026-10-26): the dispute refund's FROZEN
     * operation key, pinned pure — the finding's own rollback window. A
     * partial refund X executes at the PSP inside a resolve transaction
     * that rolls back; the charge.refunded webhook then syncs the local
     * books to X, so the state-derived key's refunded-so-far leg drifts
     * and the retry would pay X a SECOND time. The dispute-scoped key
     * carries neither the amount nor the refunded-so-far state: the same
     * dispute re-derives the SAME key across the whole window — and a
     * re-approved amount reconciles against the SAME operation instead of
     * opening a second refund.
     */
    @Test
    void disputeScopedRefundIdempotencyKey_freezesTheOperationAcrossTheRollbackWindow() {
        UUID paymentId = UUID.randomUUID();
        UUID disputeId = UUID.randomUUID();
        UUID otherDispute = UUID.randomUUID();

        String firstAttempt = PaymentsService.disputeScopedRefundIdempotencyKey(paymentId, disputeId);
        String retryAfterWebhookSync = PaymentsService.disputeScopedRefundIdempotencyKey(paymentId, disputeId);
        String reApprovedAmount = PaymentsService.disputeScopedRefundIdempotencyKey(paymentId, disputeId);
        String sibling = PaymentsService.disputeScopedRefundIdempotencyKey(paymentId, otherDispute);

        assertEquals("marketplace-refund-" + paymentId + "-dispute-" + disputeId, firstAttempt);
        assertEquals(firstAttempt, retryAfterWebhookSync,
                "the retry after the webhook's cumulative sync re-derives the SAME key");
        assertEquals(firstAttempt, reApprovedAmount,
                "a re-approved amount reconciles against the SAME operation");
        assertNotEquals(firstAttempt, sibling, "a sibling dispute's key differs");
        assertNotEquals(firstAttempt,
                PaymentsService.refundIdempotencyKey(paymentId, 1000L, 1500L),
                "the frozen operation key is never the state-derived one");
    }

    /** Acceptance 3: the remote cumulative wins over the local sum (divergence case). */
    @Test
    void refundPayment_partialRefundAppliesRemoteCumulative() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L).payment();

        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(800L, result.getRefundedAmountCents());
        assertEquals(Long.valueOf(800L), intent.getRefundedAmountCents());
    }

    /** Acceptance 4 mirror: channel bound but intent unlinked — internal path, no remote call. */
    @Test
    void refundPayment_unlinkedIntent_keepsInternalPath() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_r3");
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L).payment();

        verify(pspChannel, never()).createRemoteRefund(any(), any(), any());
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, result.getStatus());
        assertEquals(400L, result.getRefundedAmountCents());
    }

    /** Acceptance 2 + CodeRabbit round 1: a PENDING remote refund moves no money yet — books untouched. */
    @Test
    void refundPayment_pendingRemoteRefund_leavesLocalBooksUntouched() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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

        Payment result = service(boundChannel).refundPayment(payment.getId(), 400L).payment();

        assertEquals(PaymentStatus.COMPLETED, result.getStatus());
        assertEquals(0L, result.getRefundedAmountCents());
        verify(intentRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /** A non-succeeded/non-pending remote status is a loud conflict — no silent local advance. */
    @Test
    void refundPayment_failedRemoteRefundStatus_throwsConflict() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
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

    /**
     * R10 — the recorder's inbox contract: the row is born RECEIVED carrying
     * the COMPLETE re-delivery contract — the raw provider payload, the
     * resolved intent id, the external (PSP) id, and the refund snapshot
     * amount — everything the recovery sweep needs to re-deliver the event
     * without the channel being bound.
     */
    @Test
    void handleStripeWebhook_recordsTheFullInboxContractAsReceived() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByProviderAndEventId("stripe", "evt_inbox_1")).thenReturn(Optional.empty());
        when(webhookEventRepository.saveAndFlush(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        UUID intentId = intent.getId();
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intentId, 5000L);
        payment.markCompleted("ch_inbox");
        when(pspChannel.verifyWebhook("raw_payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_inbox_1", "charge.refunded",
                        intentId, "pi_remote_inbox", new PspChannel.RefundSnapshot(300L)));
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));

        boolean created = service(boundChannel).handleStripeWebhook("raw_payload", "t=1,v1=sig");

        assertTrue(created);
        org.mockito.ArgumentCaptor<PaymentWebhookEvent> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(webhookEventRepository).saveAndFlush(captor.capture());
        PaymentWebhookEvent recorded = captor.getValue();
        assertEquals(WebhookProcessingState.RECEIVED, recorded.getProcessingState());
        assertEquals("raw_payload", recorded.getPayload());
        assertEquals(intentId, recorded.getPaymentIntentId());
        assertEquals("pi_remote_inbox", recorded.getExternalId());
        assertEquals(Long.valueOf(300L), recorded.getRefundAmountCents());
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

    // ---- R1 (Wave 2): the booking-cancellation listener refunds through the ONE
    // (merge note, waves 2+4): the mocks below stub the R4 COLLECTED scoped search
    // (SUCCEEDED intents = money-carrying) — the unfiltered findByBookingId the R1
    // tests were written against was retired by wave 4's one-attempt invariant.
    // channel contract — no dual behaviors for one financial operation ----

    /**
     * R1's core regression: a booking cancellation refunding a remotely-charged
     * intent MUST create the remote refund (the pre-fix behavior marked the
     * local books REFUNDED while the provider never paid anyone back). The
     * full-refund key is derived from the request state exactly like the admin
     * path's — one contract, two callers.
     */
    @Test
    void autoRefundByBooking_linkedIntent_refundsThroughTheChannelOnceWithDerivedKey() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(bookingId, UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_a1");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_a1");
        when(intentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
                bookingId, PaymentIntentStatus.COLLECTED)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.of(payment));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(eq("pi_remote_a1"), eq(null),
                eq("marketplace-refund-" + payment.getId() + "-0-full")))
                .thenReturn(new PspChannel.RemoteRefund("re_a1", "succeeded", 5000L));

        service(boundChannel).autoRefundByBooking(bookingId);

        // Exactly ONE remote refund, replay-key derived from the full-refund
        // request state (a listener retry re-derives the SAME key).
        verify(pspChannel, times(1)).createRemoteRefund(eq("pi_remote_a1"), eq(null), anyString());
        assertEquals(PaymentIntentStatus.REFUNDED, intent.getStatus());
        assertEquals(5000L, intent.getRefundedAmountCents());
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
        assertEquals(5000L, payment.getRefundedAmountCents());
        verify(eventPublisher).publishEvent(
                new com.marketplace.shared.api.PaymentStateChangedEvent(intent.getId(), "REFUNDED"));
    }

    /**
     * The honesty guarantee (plan R1 point 3): a channel failure must NOT mark
     * the intent REFUNDED — the exception propagates to the listener's
     * {@code @Retry(name = "paymentProcessing")} wrapper and, failing that, to
     * Spring Modulith's Event Publication Registry, which re-delivers until
     * the channel heals. The local books keep the true money state throughout.
     */
    @Test
    void autoRefundByBooking_channelFailure_leavesIntentUnrefundedAndRetryable() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(bookingId, UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_a2");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_a2");
        when(intentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
                bookingId, PaymentIntentStatus.COLLECTED)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(any(), any(), anyString()))
                .thenThrow(new PspChannelException("provider unreachable"));

        assertThrows(PspChannelException.class, () -> service(boundChannel).autoRefundByBooking(bookingId));

        // Not a single false book: the intent and payment keep their true
        // SUCCEEDED/COMPLETED state, and no REFUNDED event ever fires.
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        verify(intentRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /**
     * A PENDING remote refund completes the listener cleanly: nothing is
     * falsely refunded, and the {@code charge.refunded} webhook (the async
     * safety net) finishes the books when the provider completes the refund.
     */
    @Test
    void autoRefundByBooking_pendingRemoteRefund_completesCleanlyAndWaitsForWebhook() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(bookingId, UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_a3");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_a3");
        when(intentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
                bookingId, PaymentIntentStatus.COLLECTED)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(pspChannel.createRemoteRefund(any(), any(), anyString()))
                .thenReturn(new PspChannel.RemoteRefund("re_a3", "pending", 0L));

        service(boundChannel).autoRefundByBooking(bookingId);

        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
        assertEquals(PaymentStatus.COMPLETED, payment.getStatus());
        verify(intentRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /**
     * Channel bound but intent unlinked (internal/test money): the listener's
     * documented inert local path stays byte-compatible with the pre-R1
     * behavior — full local refund, no remote call.
     */
    @Test
    void autoRefundByBooking_unlinkedIntent_keepsTheInertLocalPath() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(bookingId, UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_a4");
        when(intentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
                bookingId, PaymentIntentStatus.COLLECTED)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.of(payment));
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);

        service(boundChannel).autoRefundByBooking(bookingId);

        verify(pspChannel, never()).createRemoteRefund(any(), any(), any());
        assertEquals(PaymentIntentStatus.REFUNDED, intent.getStatus());
        assertEquals(PaymentStatus.REFUNDED, payment.getStatus());
    }

    /**
     * The no-payment-row edge (never charged through settlement): the
     * intent-level terminal mark is the honest state — nothing to coordinate.
     */
    @Test
    void autoRefundByBooking_noPaymentRow_marksTheIntentLevelRefund() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.create(bookingId, UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        when(intentRepository.findFirstByBookingIdAndStatusInOrderByCreatedAtDescIdDesc(
                bookingId, PaymentIntentStatus.COLLECTED)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.empty());
        when(intentRepository.save(any(PaymentIntent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);

        service(boundChannel).autoRefundByBooking(bookingId);

        // No payment row exists, so no remote coordination is derivable — the
        // intent's own terminal mark applies (the pre-R1 behavior preserved).
        verify(pspChannel, never()).createRemoteRefund(any(), any(), any());
        assertEquals(PaymentIntentStatus.REFUNDED, intent.getStatus());
    }
}
