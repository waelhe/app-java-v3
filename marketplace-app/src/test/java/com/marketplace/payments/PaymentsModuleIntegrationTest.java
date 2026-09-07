package com.marketplace.payments;

import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ModuleTestConfig.class, PaymentsModuleIntegrationTest.RealChannelConfig.class})
@WithMockUser
class PaymentsModuleIntegrationTest {

    /**
     * Binds a REAL StripePspChannel with throwaway test credentials so the
     * verified-webhook path (signature verification + event parsing) runs
     * through the production code — the sync path makes no network calls.
     * The conditional production config stays inert in the test profile.
     */
    @TestConfiguration
    static class RealChannelConfig {
        @Bean
        PspChannel testStripeChannel() {
            return new StripePspChannel("sk_test_inert", "whsec_test_webhook_secret");
        }
    }

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private PaymentsService paymentsService;

    @Autowired
    private PaymentWebhookSecurity paymentWebhookSecurity;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void contextLoads() {
    }

    @Test
    void listIntents_returnsEmptyPage() {
        var page = paymentsService.listIntents(Pageable.ofSize(10));
        assertThat(page).isEmpty();
    }

    @Test
    void processWebhookEvent_returnsTrue() {
        String signature = paymentWebhookSecurity.computeSignature("evt_testpayment_intent.succeeded");
        var result = paymentsService.processWebhookEvent("stripe", "evt_test", "payment_intent.succeeded", signature);
        assertThat(result).isTrue();
    }

    /**
     * L19 acceptance 1 (roadmap §5): a webhook payment_intent.payment_failed
     * flips the intent AND the payment to FAILED. The ledger side of the
     * criterion (nothing credited) is structural — the ledger listener
     * reacts to COMPLETED only, pinned by
     * {@code LedgerPaymentEventListenerTest.ignoresNonCompletedEvents}.
     */
    @Test
    void webhook_paymentFailed_flipsIntentAndPaymentToFailed() {
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent = paymentIntentRepository.save(intent);
        Payment payment = paymentRepository.save(Payment.create(intent.getId(), 5000L));
        String eventId = "evt_fail_" + UUID.randomUUID();

        String signature = paymentWebhookSecurity.computeSignature(eventId + "payment_intent.payment_failed");
        boolean created = paymentsService.processWebhookEvent("stripe", eventId,
                "payment_intent.payment_failed", signature, intent.getId(), null);

        assertThat(created).isTrue();
        assertThat(paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    /**
     * L19 acceptance 1 — the stripe route with a REAL signature: the same
     * failure transition driven through the production verifier instead of
     * the legacy HMAC channel.
     */
    @Test
    void stripeWebhook_paymentFailed_flipsIntentToFailed() {
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent.assignPspIntentId("pi_fail_route");
        intent = paymentIntentRepository.save(intent);
        paymentRepository.save(Payment.create(intent.getId(), 5000L));

        String payload = """
                {"id":"evt_%s","type":"payment_intent.payment_failed","api_version":"2024-06-20",\
                "data":{"object":{"id":"pi_fail_route","object":"payment_intent",\
                "metadata":{"marketplace_intent_id":"%s"}}}}
                """.formatted(UUID.randomUUID(), intent.getId());

        boolean created = paymentsService.handleStripeWebhook(payload, stripeSignature(payload));

        assertThat(created).isTrue();
        assertThat(paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentIntentStatus.FAILED);
    }

    /**
     * L19 (c): a signed {@code charge.refunded} notification syncs the local
     * books to the remote cumulative actual — the async safety net for
     * refunds completed outside this service (e.g. the Stripe dashboard).
     * Runs the REAL StripePspChannel verifier; the sync path is DB-only.
     */
    @Test
    void stripeWebhook_chargeRefunded_syncsLocalBooksToRemoteActual() {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_refund_route");
        intent = paymentIntentRepository.save(intent);
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_refund_route");
        payment = paymentRepository.save(payment);

        String payload = """
                {"id":"evt_%s","type":"charge.refunded","api_version":"2024-06-20",\
                "data":{"object":{"id":"ch_refund_route","object":"charge",\
                "payment_intent":"pi_refund_route","amount":5000,"amount_refunded":350,\
                "refunds":{"object":"list","data":[]}}}}
                """.formatted(UUID.randomUUID());

        boolean created = paymentsService.handleStripeWebhook(payload, stripeSignature(payload));

        assertThat(created).isTrue();
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getRefundedAmountCents())
                .isEqualTo(350L);
        assertThat(paymentIntentRepository.findById(intent.getId()).orElseThrow().getRefundedAmountCents())
                .isEqualTo(350L);
    }

    /**
     * Computes a valid Stripe-Signature header for the test secret — the
     * official scheme: {@code t=<epoch>,v1=hex(hmac_sha256(secret,
     * "<t>.<payload>"))}, verified inside the SDK's DEFAULT_TOLERANCE.
     */
    private String stripeSignature(String payload) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    "whsec_test_webhook_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
            return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("test signature computation failed", e);
        }
    }
}
