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
                eventPublisher, currentUserProvider, bookingParticipantProvider, webhookSecurity, channel);
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
        when(webhookEventRepository.findByEventId("evt_9")).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_9", "payment_intent.succeeded",
                        intentId, "pi_remote_9"));
        intent.markProcessing();
        when(intentRepository.findById(intentId)).thenReturn(Optional.of(intent));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertTrue(created);
        verify(webhookEventRepository).save(argThat(ev -> "stripe".equals(ev.getProvider())
                && "evt_9".equals(ev.getEventId())));
        assertEquals(PaymentIntentStatus.SUCCEEDED, intent.getStatus());
    }

    @Test
    void handleStripeWebhook_pspLinkFallback_resolvesIntentWithoutMetadata() {
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        when(webhookEventRepository.findByEventId("evt_10")).thenReturn(Optional.empty());
        when(webhookEventRepository.save(any(PaymentWebhookEvent.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        UUID intentId = intent.getId();
        when(pspChannel.verifyWebhook("payload", "t=1,v1=sig"))
                .thenReturn(new PspChannel.VerifiedWebhook("evt_10", "payment_intent.succeeded",
                        null, "pi_remote_10"));
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
                        intentId, "pi_11"));
        when(webhookEventRepository.findByEventId("evt_11")).thenReturn(Optional.of(mock(PaymentWebhookEvent.class)));

        boolean created = service(boundChannel).handleStripeWebhook("payload", "t=1,v1=sig");

        assertFalse(created, "a replayed Stripe notification must not re-dispatch");
        verify(webhookEventRepository, never()).save(any());
    }

    @Test
    void assignPspIntentId_repeatedSameLink_isIdempotent_conflictRejected() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 100L, null);
        intent.assignPspIntentId("pi_a");
        intent.assignPspIntentId("pi_a");
        assertEquals("pi_a", intent.getPspIntentId());
        assertThrows(IllegalStateException.class, () -> intent.assignPspIntentId("pi_b"));
    }
}
