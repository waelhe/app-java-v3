package com.marketplace.payments;

import com.stripe.net.Webhook;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.springframework.security.access.AccessDeniedException;

/**
 * Verifies the Stripe channel's webhook verification with the SDK's own
 * REAL crypto — no network anywhere. The official signature header is
 * generated locally (Webhook.Signature.generateSignatureHeader — an SDK
 * utility that computes the same t=/v1= HMAC-SHA256 header Stripe sends),
 * then verified through the same constructEvent path production uses.
 * Official facts pinned here (sources jar 33.4.1 + docs cached at
 * scripts/psp-doc-verify/): constructEvent throws SignatureVerificationException
 * when "at least one of the three parameters ... is incorrect";
 * DEFAULT_TOLERANCE = 300 seconds.
 */
class StripePspChannelTest {

    private static final String SECRET = "whsec_test_secret";

    private final StripePspChannel channel = new StripePspChannel("sk_test_key", SECRET);

    private static String stripeEvent(UUID intentId, String pspIntentId) {
        String metadata = intentId == null
                ? ""
                : "\"metadata\":{\"marketplace_intent_id\":\"" + intentId + "\"},";
        return "{\"id\":\"evt_2001\",\"object\":\"event\",\"type\":\"payment_intent.succeeded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"" + pspIntentId + "\",\"object\":\"payment_intent\","
                + "\"amount\":5000,\"currency\":\"sar\"," + metadata + "\"status\":\"succeeded\"}}}";
    }

    @Test
    void verifyWebhook_acceptsLocallyGeneratedRealSignatureAndExtractsFields() throws Exception {
        UUID intentId = UUID.randomUUID();
        String payload = stripeEvent(intentId, "pi_test_100");
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);

        PspChannel.VerifiedWebhook verified = channel.verifyWebhook(payload, signature);

        assertThat(verified.eventId()).isEqualTo("evt_2001");
        assertThat(verified.eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(verified.marketplaceIntentId()).isEqualTo(intentId);
        assertThat(verified.pspIntentId()).isEqualTo("pi_test_100");
    }

    @Test
    void verifyWebhook_rejectsTamperedPayload() throws Exception {
        String payload = stripeEvent(UUID.randomUUID(), "pi_test_100");
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);
        String tampered = payload.replace("5000", "9999");

        assertThatThrownBy(() -> channel.verifyWebhook(tampered, signature))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("verification failed");
    }

    @Test
    void verifyWebhook_rejectsWrongSecret() throws Exception {
        String payload = stripeEvent(UUID.randomUUID(), "pi_test_100");
        String signature = Webhook.Signature.generateSignatureHeader(payload, "whsec_other_secret");

        assertThatThrownBy(() -> channel.verifyWebhook(payload, signature))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("verification failed");
    }

    @Test
    void verifyWebhook_rejectsMissingSignature() {
        String payload = stripeEvent(UUID.randomUUID(), "pi_test_100");

        assertThatThrownBy(() -> channel.verifyWebhook(payload, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void verifyWebhook_rejectsStaleTimestampOutsideTolerance() throws Exception {
        String payload = stripeEvent(UUID.randomUUID(), "pi_test_100");
        // 400 seconds old — beyond the official DEFAULT_TOLERANCE of 300.
        long oldTimestamp = System.currentTimeMillis() / 1000 - 400;
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET, oldTimestamp);

        assertThatThrownBy(() -> channel.verifyWebhook(payload, signature))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("verification failed");
    }

    @Test
    void verifyWebhook_toleratesEventWithoutPaymentIntentObject() throws Exception {
        String payload = "{\"id\":\"evt_2002\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"ch_1\",\"object\":\"charge\"}}}";
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);

        PspChannel.VerifiedWebhook verified = channel.verifyWebhook(payload, signature);

        // Non-payment-intent events carry no intent ids — dispatch treats
        // unknown types as debug noise, exactly like the legacy channel.
        assertThat(verified.eventType()).isEqualTo("charge.refunded");
        assertThat(verified.marketplaceIntentId()).isNull();
        assertThat(verified.pspIntentId()).isNull();
    }

    @Test
    void verifyWebhook_toleratesMetadataWithoutIntentId() throws Exception {
        String payload = stripeEvent(null, "pi_test_300");
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);

        PspChannel.VerifiedWebhook verified = channel.verifyWebhook(payload, signature);

        assertThat(verified.pspIntentId()).isEqualTo("pi_test_300");
        assertThat(verified.marketplaceIntentId()).isNull();
    }

    @Test
    void verifyWebhook_carriesTheExecutedRefundsIdentities() throws Exception {
        // CodeRabbit round 2 on #828: the charge.refunded payload carries the
        // charge's completed refunds. A refund created through this service's
        // contract carries the operation key as its metadata — the identity
        // the webhook settle matches; a pending refund is not execution
        // evidence; a dashboard-created refund carries no key at all.
        String payload = "{\"id\":\"evt_2003\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_9\",\"object\":\"charge\",\"payment_intent\":\"pi_remote_r9\","
                + "\"amount_refunded\":2000,"
                + "\"refunds\":{\"object\":\"list\",\"data\":["
                + "{\"id\":\"re_r9\",\"object\":\"refund\",\"status\":\"succeeded\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":"
                + "\"marketplace-refund-p1-return-r1-2000\"}},"
                + "{\"id\":\"re_pending\",\"object\":\"refund\",\"status\":\"pending\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":"
                + "\"marketplace-refund-p1-return-r2-500\"}},"
                + "{\"id\":\"re_dash\",\"object\":\"refund\",\"status\":\"succeeded\",\"metadata\":{}}"
                + "]}}}}";
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);

        PspChannel.VerifiedWebhook verified = channel.verifyWebhook(payload, signature);

        assertThat(verified.eventType()).isEqualTo("charge.refunded");
        assertThat(verified.pspIntentId()).isEqualTo("pi_remote_r9");
        assertThat(verified.refund().refundedAmountCents()).isEqualTo(2000L);
        assertThat(verified.refund().executedRefunds()).containsExactly(
                new PspChannel.ExecutedRefund("re_r9", "marketplace-refund-p1-return-r1-2000"));
    }
}
