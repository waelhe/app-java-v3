package com.marketplace.payments;

import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.RefundCollection;
import com.stripe.model.StripeObject;
import com.stripe.net.ApiMode;
import com.stripe.net.ApiResource;
import com.stripe.net.BaseAddress;
import com.stripe.net.BearerTokenAuthenticator;
import com.stripe.net.LiveStripeResponseGetter;
import com.stripe.net.RequestOptions;
import com.stripe.net.StripeResponseGetter;
import com.stripe.net.Webhook;
import com.stripe.param.ChargeRetrieveParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

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
        // the pending refund is filtered (not execution evidence); the
        // dashboard refund IS carried with a null key — the snapshot stays a
        // faithful representation of the provider's completed refunds, and
        // the service's settle skips keyless entries
        assertThat(verified.refund().executedRefunds()).containsExactly(
                new PspChannel.ExecutedRefund("re_r9", "marketplace-refund-p1-return-r1-2000"),
                new PspChannel.ExecutedRefund("re_dash", null));
    }

    @Test
    void createRemoteRefund_reconcilesTheExistingKeyedRefundInsteadOfCreating() throws Exception {
        // CodeRabbit round 4: the provider's idempotency records expire after
        // 24 hours — past that window a retry of the SAME operation must
        // return the refund already carrying the operation's key, never
        // create a second one.
        com.stripe.model.Refund existing = Mockito.mock(com.stripe.model.Refund.class);
        Mockito.when(existing.getMetadata()).thenReturn(
                java.util.Map.of("marketplace_refund_operation_key", "op-key-1"));
        Mockito.when(existing.getId()).thenReturn("re_exist");
        Mockito.when(existing.getStatus()).thenReturn("succeeded");
        Mockito.when(existing.getCharge()).thenReturn("ch_x");
        RefundCollection collection = Mockito.mock(RefundCollection.class);
        Mockito.when(collection.autoPagingIterable()).thenReturn(java.util.List.of(existing));
        // the cumulative answer: a charge extracted through the official
        // event deserialization (no network)
        String chargeEvent = "{\"id\":\"evt_chx\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"ch_x\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_x\",\"amount_refunded\":2000}}}";
        Event chargeEvt = Webhook.constructEvent(chargeEvent,
                Webhook.Signature.generateSignatureHeader(chargeEvent, SECRET), SECRET);
        Charge chargeWithCumulative = (Charge) chargeEvt.getDataObjectDeserializer().getObject()
                .orElseGet(() -> { try { return chargeEvt.getDataObjectDeserializer().deserializeUnsafe(); }
                    catch (Exception e) { throw new IllegalStateException(e); } });

        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class);
             MockedStatic<Charge> chargeMock = mockStatic(Charge.class)) {
            refundMock.when(() -> com.stripe.model.Refund.list(
                            org.mockito.ArgumentMatchers.any(RefundListParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(collection);
            chargeMock.when(() -> Charge.retrieve(org.mockito.ArgumentMatchers.eq("ch_x"),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(chargeWithCumulative);

            PspChannel.RemoteRefund outcome = channel.createRemoteRefund("pi_x", 1500L, "op-key-1");

            org.assertj.core.api.Assertions.assertThat(outcome.refundId()).isEqualTo("re_exist");
            org.assertj.core.api.Assertions.assertThat(outcome.status()).isEqualTo("succeeded");
            org.assertj.core.api.Assertions.assertThat(outcome.refundedTotalCents()).isEqualTo(2000L);
            refundMock.verify(() -> com.stripe.model.Refund.create(
                            org.mockito.ArgumentMatchers.any(RefundCreateParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)),
                    org.mockito.Mockito.never());
        }
    }

    @Test
    void createRemoteRefund_createsWhenNoKeyedRefundExists() throws Exception {
        // the first submission: the reconcile finds nothing under the
        // operation's key, and the create path answers with the new refund.
        RefundCollection empty = Mockito.mock(RefundCollection.class);
        Mockito.when(empty.autoPagingIterable()).thenReturn(java.util.List.of());
        com.stripe.model.Refund created = Mockito.mock(com.stripe.model.Refund.class);
        Mockito.when(created.getId()).thenReturn("re_new");
        Mockito.when(created.getStatus()).thenReturn("succeeded");
        Mockito.when(created.getCharge()).thenReturn("ch_y");
        String chargeEvent = "{\"id\":\"evt_chy\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"ch_y\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_x\",\"amount_refunded\":1500}}}";
        Event chargeEvt = Webhook.constructEvent(chargeEvent,
                Webhook.Signature.generateSignatureHeader(chargeEvent, SECRET), SECRET);
        Charge chargeWithCumulative = (Charge) chargeEvt.getDataObjectDeserializer().getObject()
                .orElseGet(() -> { try { return chargeEvt.getDataObjectDeserializer().deserializeUnsafe(); }
                    catch (Exception e) { throw new IllegalStateException(e); } });

        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class);
             MockedStatic<Charge> chargeMock = mockStatic(Charge.class)) {
            refundMock.when(() -> com.stripe.model.Refund.list(
                            org.mockito.ArgumentMatchers.any(RefundListParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(empty);
            refundMock.when(() -> com.stripe.model.Refund.create(
                            org.mockito.ArgumentMatchers.any(RefundCreateParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(created);
            chargeMock.when(() -> Charge.retrieve(org.mockito.ArgumentMatchers.eq("ch_y"),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(chargeWithCumulative);

            PspChannel.RemoteRefund outcome = channel.createRemoteRefund("pi_x", 1500L, "op-key-2");

            org.assertj.core.api.Assertions.assertThat(outcome.refundId()).isEqualTo("re_new");
            org.assertj.core.api.Assertions.assertThat(outcome.refundedTotalCents()).isEqualTo(1500L);
            refundMock.verify(() -> com.stripe.model.Refund.create(
                            org.mockito.ArgumentMatchers.any(RefundCreateParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)));
        }
    }

    @Test
    void createRemoteRefund_skipsADeadMatchAndReconcilesTheLiveOne() throws Exception {
        // CodeRabbit round 5: a matching refund whose provider status is
        // failed/canceled is not the operation's money — the scan continues
        // (across the remaining pages, simulated as one iterable) and the
        // LIVE match under the same key is the one reconciled.
        com.stripe.model.Refund dead = Mockito.mock(com.stripe.model.Refund.class);
        Mockito.when(dead.getMetadata()).thenReturn(
                java.util.Map.of("marketplace_refund_operation_key", "op-key-3"));
        Mockito.when(dead.getId()).thenReturn("re_dead");
        Mockito.when(dead.getStatus()).thenReturn("failed");
        com.stripe.model.Refund live = Mockito.mock(com.stripe.model.Refund.class);
        Mockito.when(live.getMetadata()).thenReturn(
                java.util.Map.of("marketplace_refund_operation_key", "op-key-3"));
        Mockito.when(live.getId()).thenReturn("re_live");
        Mockito.when(live.getStatus()).thenReturn("succeeded");
        Mockito.when(live.getCharge()).thenReturn("ch_z");
        RefundCollection collection = Mockito.mock(RefundCollection.class);
        Mockito.when(collection.autoPagingIterable()).thenReturn(java.util.List.of(dead, live));
        String chargeEvent = "{\"id\":\"evt_chz\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"ch_z\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_z\",\"amount_refunded\":2000}}}";
        Event chargeEvt = Webhook.constructEvent(chargeEvent,
                Webhook.Signature.generateSignatureHeader(chargeEvent, SECRET), SECRET);
        Charge chargeWithCumulative = (Charge) chargeEvt.getDataObjectDeserializer().getObject()
                .orElseGet(() -> { try { return chargeEvt.getDataObjectDeserializer().deserializeUnsafe(); }
                    catch (Exception e) { throw new IllegalStateException(e); } });

        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class);
             MockedStatic<Charge> chargeMock = mockStatic(Charge.class)) {
            refundMock.when(() -> com.stripe.model.Refund.list(
                            org.mockito.ArgumentMatchers.any(RefundListParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(collection);
            chargeMock.when(() -> Charge.retrieve(org.mockito.ArgumentMatchers.eq("ch_z"),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(chargeWithCumulative);

            PspChannel.RemoteRefund outcome = channel.createRemoteRefund("pi_z", 1500L, "op-key-3");

            org.assertj.core.api.Assertions.assertThat(outcome.refundId()).isEqualTo("re_live");
            org.assertj.core.api.Assertions.assertThat(outcome.status()).isEqualTo("succeeded");
            refundMock.verify(() -> com.stripe.model.Refund.create(
                            org.mockito.ArgumentMatchers.any(RefundCreateParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)),
                    org.mockito.Mockito.never());
        }
    }

    @Test
    void createRemoteRefund_createsWhenEveryMatchUnderTheKeyIsDead() throws Exception {
        // the multi-page shape (the iterable carries the whole scan): every
        // refund carrying the operation's key is dead — a fresh one is
        // created for the retry.
        com.stripe.model.Refund dead = Mockito.mock(com.stripe.model.Refund.class);
        Mockito.when(dead.getMetadata()).thenReturn(
                java.util.Map.of("marketplace_refund_operation_key", "op-key-4"));
        Mockito.when(dead.getId()).thenReturn("re_dead4");
        Mockito.when(dead.getStatus()).thenReturn("canceled");
        RefundCollection collection = Mockito.mock(RefundCollection.class);
        Mockito.when(collection.autoPagingIterable()).thenReturn(java.util.List.of(dead));
        com.stripe.model.Refund created = Mockito.mock(com.stripe.model.Refund.class);
        Mockito.when(created.getId()).thenReturn("re_fresh");
        Mockito.when(created.getStatus()).thenReturn("succeeded");
        Mockito.when(created.getCharge()).thenReturn("ch_w");
        String chargeEvent = "{\"id\":\"evt_chw\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"ch_w\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_w\",\"amount_refunded\":1500}}}";
        Event chargeEvt = Webhook.constructEvent(chargeEvent,
                Webhook.Signature.generateSignatureHeader(chargeEvent, SECRET), SECRET);
        Charge chargeWithCumulative = (Charge) chargeEvt.getDataObjectDeserializer().getObject()
                .orElseGet(() -> { try { return chargeEvt.getDataObjectDeserializer().deserializeUnsafe(); }
                    catch (Exception e) { throw new IllegalStateException(e); } });

        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class);
             MockedStatic<Charge> chargeMock = mockStatic(Charge.class)) {
            refundMock.when(() -> com.stripe.model.Refund.list(
                            org.mockito.ArgumentMatchers.any(RefundListParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(collection);
            refundMock.when(() -> com.stripe.model.Refund.create(
                            org.mockito.ArgumentMatchers.any(RefundCreateParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(created);
            chargeMock.when(() -> Charge.retrieve(org.mockito.ArgumentMatchers.eq("ch_w"),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(chargeWithCumulative);

            PspChannel.RemoteRefund outcome = channel.createRemoteRefund("pi_w", 1500L, "op-key-4");

            org.assertj.core.api.Assertions.assertThat(outcome.refundId()).isEqualTo("re_fresh");
            refundMock.verify(() -> com.stripe.model.Refund.create(
                            org.mockito.ArgumentMatchers.any(RefundCreateParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)));
        }
    }

    @Test
    void verifyWebhook_retrievesTheRefundIdentitiesWhenThePayloadOmitsThem() throws Exception {
        // CodeRabbit round 3: a charge.refunded payload may omit the
        // expandable refunds collection — answering an empty list there
        // would sync the books to the cumulative WITHOUT the identity settle,
        // recreating the stuck window. On omission the charge is re-retrieved
        // with the documented expand mechanism; the retrieval's answer is
        // built through the same official event deserialization production
        // uses (no network anywhere — the static retrieve is mocked with the
        // SDK's real crypto verifying the surrounding webhook).
        // the event's STALE cumulative (1000) differs from the retrieved
        // charge's fresher one (2500) — the retrieved number is the answer
        // (CodeRabbit round 4: same API snapshot the identities came from)
        String retrievalPayload = "{\"id\":\"evt_retr\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_10\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_r10\",\"amount_refunded\":2500,"
                + "\"refunds\":{\"object\":\"list\",\"data\":["
                + "{\"id\":\"re_r10\",\"object\":\"refund\",\"status\":\"succeeded\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":"
                + "\"marketplace-refund-p10-return-r10-1500\"}}"
                + "]}}}}";
        Event retrievalEvent = Webhook.constructEvent(retrievalPayload,
                Webhook.Signature.generateSignatureHeader(retrievalPayload, SECRET), SECRET);
        // the channel's own tolerant extraction pattern: the safe
        // deserialization refuses on an API-version mismatch, the documented
        // fallback is deserializeUnsafe()
        Charge retrievedCharge;
        var deserializer = retrievalEvent.getDataObjectDeserializer();
        if (deserializer.getObject().isPresent()
                && deserializer.getObject().get() instanceof Charge charge) {
            retrievedCharge = charge;
        } else {
            retrievedCharge = (Charge) deserializer.deserializeUnsafe();
        }

        String payload = "{\"id\":\"evt_2004\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_10\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_r10\",\"amount_refunded\":1000}}}";
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);

        try (MockedStatic<Charge> chargeMock = mockStatic(Charge.class)) {
            chargeMock.when(() -> Charge.retrieve(eq("ch_10"), any(ChargeRetrieveParams.class),
                            any(RequestOptions.class)))
                    .thenReturn(retrievedCharge);

            PspChannel.VerifiedWebhook verified = channel.verifyWebhook(payload, signature);

            assertThat(verified.eventType()).isEqualTo("charge.refunded");
            assertThat(verified.pspIntentId()).isEqualTo("pi_r10");
            // the RETRIEVED cumulative wins over the event's stale one
            assertThat(verified.refund().refundedAmountCents()).isEqualTo(2500L);
            assertThat(verified.refund().executedRefunds()).containsExactly(
                    new PspChannel.ExecutedRefund("re_r10", "marketplace-refund-p10-return-r10-1500"));
        }
    }

    @Test
    void verifyWebhook_carriesTheRefundEventsIdentity() throws Exception {
        // CodeRabbit round 6: refund.updated carries the Refund object itself
        // — the identity vehicle for a refund that succeeded asynchronously.
        // A succeeded refund with the operation key carries its identity; a
        // pending one carries none (not execution evidence).
        String payload = "{\"id\":\"evt_ru\",\"object\":\"event\",\"type\":\"refund.updated\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"re_late\",\"object\":\"refund\","
                + "\"status\":\"succeeded\",\"payment_intent\":\"pi_late\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":"
                + "\"marketplace-refund-pL-return-rL-2000\"}}}}";
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);

        PspChannel.VerifiedWebhook verified = channel.verifyWebhook(payload, signature);

        org.assertj.core.api.Assertions.assertThat(verified.eventType()).isEqualTo("refund.updated");
        org.assertj.core.api.Assertions.assertThat(verified.pspIntentId()).isEqualTo("pi_late");
        org.assertj.core.api.Assertions.assertThat(verified.refund().executedRefunds()).containsExactly(
                new PspChannel.ExecutedRefund("re_late", "marketplace-refund-pL-return-rL-2000"));

        String pending = "{\"id\":\"evt_rp\",\"object\":\"event\",\"type\":\"refund.created\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"re_pend\",\"object\":\"refund\","
                + "\"status\":\"pending\",\"payment_intent\":\"pi_late\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":"
                + "\"marketplace-refund-pL-return-rL-2000\"}}}}";
        String pendingSignature = Webhook.Signature.generateSignatureHeader(pending, SECRET);

        PspChannel.VerifiedWebhook pendingVerified = channel.verifyWebhook(pending, pendingSignature);

        org.assertj.core.api.Assertions.assertThat(pendingVerified.eventType()).isEqualTo("refund.created");
        org.assertj.core.api.Assertions.assertThat(pendingVerified.refund().executedRefunds()).isEmpty();
    }

    @Test
    void verifyWebhook_pagesTheRetrievedRefundsWithTheChannelKeyRequestOptions() throws Exception {
        // CodeRabbit round 9: the SDK attaches request options ONLY to
        // top-level collections — the retrieved charge's NESTED refunds
        // collection carries the response getter (Charge.setResponseGetter
        // recurses into it) but NULL request options, so the no-arg
        // auto-pagination would call the response getter's own validation
        // with null options and throw ApiKeyMissingException under the
        // per-channel key model (no global Stripe key is ever set —
        // GlobalStripeResponseGetterOptions reads Stripe.apiKey, unset
        // here and in every pipeline run). The explicit-options overload
        // validates AND attaches the channel key, so the page-2 fetch
        // rides it. This test walks the REAL PagingIterator over a REAL
        // SDK collection (has_more=true) and answers page 2 through a
        // stub whose validation delegates to the SDK's own
        // LiveStripeResponseGetter rule — no network anywhere.
        String retrievalPayload = "{\"id\":\"evt_hm1\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_hm\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_hm\",\"amount_refunded\":3000,"
                + "\"refunds\":{\"object\":\"list\",\"has_more\":true,"
                + "\"url\":\"/v1/charges/ch_hm/refunds\",\"data\":["
                + "{\"id\":\"re_p1\",\"object\":\"refund\",\"status\":\"succeeded\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":"
                + "\"marketplace-refund-pHM-return-rHM-1500\"}}"
                + "]}}}}";
        Charge retrievedCharge = chargeFromSignedEvent(retrievalPayload);
        RefundCollection firstPage = retrievedCharge.getRefunds();
        org.assertj.core.api.Assertions.assertThat(firstPage.getHasMore()).isTrue();

        String secondPagePayload = "{\"id\":\"evt_hm2\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_hm\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_hm\",\"amount_refunded\":3000,"
                + "\"refunds\":{\"object\":\"list\",\"has_more\":false,"
                + "\"url\":\"/v1/charges/ch_hm/refunds\",\"data\":["
                + "{\"id\":\"re_p2\",\"object\":\"refund\",\"status\":\"succeeded\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":"
                + "\"marketplace-refund-pHM-return-rHM-1500\"}}"
                + "]}}}}";
        RefundCollection secondPage = chargeFromSignedEvent(secondPagePayload).getRefunds();

        // the captured page-2 request facts (the deprecated request
        // overload receives every element as plain parameters — public
        // API, no reflection)
        AtomicReference<ApiResource.RequestMethod> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<java.util.Map<String, Object>> capturedParams = new AtomicReference<>();
        AtomicReference<RequestOptions> capturedOptions = new AtomicReference<>();
        final LiveStripeResponseGetter sdkValidator = new LiveStripeResponseGetter();
        StripeResponseGetter pageTwoGetter = new StripeResponseGetter() {
            @Override
            public <T extends StripeObject> T request(BaseAddress baseAddress,
                    ApiResource.RequestMethod method, String path,
                    java.util.Map<String, Object> params, Type typeToken,
                    RequestOptions options, ApiMode apiMode) {
                capturedMethod.set(method);
                capturedPath.set(path);
                capturedParams.set(params);
                capturedOptions.set(options);
                @SuppressWarnings("unchecked")
                T answer = (T) secondPage;
                return answer;
            }

            @Override
            public InputStream requestStream(BaseAddress baseAddress,
                    ApiResource.RequestMethod method, String path,
                    java.util.Map<String, Object> params, RequestOptions options,
                    ApiMode apiMode) {
                throw new UnsupportedOperationException("no stream requests in this test");
            }

            @Override
            public void validateRequestOptions(RequestOptions options) {
                // the SDK's own rule, verbatim — the real global getter's
                // validator: null/keyless options with no global key throw
                // ApiKeyMissingException, exactly the production failure
                // the no-arg overload would hit on this nested collection
                sdkValidator.validateRequestOptions(options);
            }
        };
        // what the API response path itself does: the nested collection
        // receives the response getter (never the request options)
        firstPage.setResponseGetter(pageTwoGetter);

        // the incoming webhook: charge.refunded WITHOUT the refunds
        // collection (the round-3 omission) and a STALE cumulative — the
        // retrieval fires, and its fresher snapshot answers
        String payload = "{\"id\":\"evt_hm0\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_hm\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_hm\",\"amount_refunded\":1000}}}";
        String signature = Webhook.Signature.generateSignatureHeader(payload, SECRET);

        try (MockedStatic<Charge> chargeMock = mockStatic(Charge.class)) {
            chargeMock.when(() -> Charge.retrieve(eq("ch_hm"), any(ChargeRetrieveParams.class),
                            any(RequestOptions.class)))
                    .thenReturn(retrievedCharge);

            PspChannel.VerifiedWebhook verified = channel.verifyWebhook(payload, signature);

            assertThat(verified.eventType()).isEqualTo("charge.refunded");
            assertThat(verified.pspIntentId()).isEqualTo("pi_hm");
            // the RETRIEVED cumulative wins over the event's stale one
            assertThat(verified.refund().refundedAmountCents()).isEqualTo(3000L);
            // the pagination crossed the page boundary: both keyed
            // identities — page 1 AND page 2 — are execution evidence
            assertThat(verified.refund().executedRefunds()).containsExactly(
                    new PspChannel.ExecutedRefund("re_p1", "marketplace-refund-pHM-return-rHM-1500"),
                    new PspChannel.ExecutedRefund("re_p2", "marketplace-refund-pHM-return-rHM-1500"));
        }

        // the page-2 fetch rode the CHANNEL KEY options — the SDK's
        // BearerTokenAuthenticator carrying this channel's key, never a
        // global key (none exists)
        org.assertj.core.api.Assertions.assertThat(capturedOptions.get()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(capturedOptions.get().getAuthenticator())
                .isInstanceOf(BearerTokenAuthenticator.class);
        org.assertj.core.api.Assertions.assertThat(
                        ((BearerTokenAuthenticator) capturedOptions.get().getAuthenticator()).getApiKey())
                .isEqualTo("sk_test_key");
        // the page-2 request: a GET on the collection's own URL (the charge
        // filter lives there) with ONLY the starting_after cursor — the
        // empty params override added nothing
        org.assertj.core.api.Assertions.assertThat(capturedMethod.get())
                .isEqualTo(ApiResource.RequestMethod.GET);
        org.assertj.core.api.Assertions.assertThat(capturedPath.get())
                .isEqualTo("/v1/charges/ch_hm/refunds");
        org.assertj.core.api.Assertions.assertThat(capturedParams.get())
                .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("starting_after", "re_p1"));
    }

    /**
     * CodeRabbit round 13 follow-up (the nitpick closed): the refund
     * search's SECOND-page fetch failure. Page 1 carries one NON-matching
     * refund and has_more=true; the page-2 request fails AT THE PAGE
     * RESPONDER (the SDK's PagingIterator.next() wraps every failed page
     * fetch — StripeException included — in a raw
     * RuntimeException("Unable to lazy-load stripe objects", e); v33.4.2
     * sources line 58) — never by mocking the iterable itself. The failure
     * must surface as the channel's PspChannelException through the
     * safePagination/paged contract (the cause unwrapped and preserved),
     * and Refund.create must NEVER run: a refund is never created on an
     * incomplete scan.
     */
    @Test
    void createRemoteRefund_aSecondPageFetchFailureAnswersTheChannelExceptionAndNeverCreates() throws Exception {
        // page 1: a REAL deserialized collection (has_more=true) carrying
        // one refund whose metadata does NOT match the operation's key —
        // the scan reaches the page boundary and fetches page 2
        String page1Payload = "{\"id\":\"evt_pfx\",\"object\":\"event\",\"type\":\"charge.refunded\","
                + "\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{"
                + "\"id\":\"ch_fx\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_fx\",\"amount_refunded\":0,"
                + "\"refunds\":{\"object\":\"list\",\"has_more\":true,"
                + "\"url\":\"/v1/charges/ch_fx/refunds\",\"data\":["
                + "{\"id\":\"re_p1fx\",\"object\":\"refund\",\"status\":\"succeeded\","
                + "\"metadata\":{\"marketplace_refund_operation_key\":\"someone-elses-key\"}}"
                + "]}}}}";
        RefundCollection firstPage = chargeFromSignedEvent(page1Payload).getRefunds();
        org.assertj.core.api.Assertions.assertThat(firstPage.getHasMore()).isTrue();

        // the page-2 responder FAILS with a checked StripeException — the
        // shape LiveStripeResponseGetter answers on a transport/API
        // failure; PagingIterator wraps it and paged() unwraps the cause
        StripeResponseGetter failingGetter = new StripeResponseGetter() {
            @Override
            public <T extends StripeObject> T request(BaseAddress baseAddress,
                    ApiResource.RequestMethod method, String path,
                    java.util.Map<String, Object> params, Type typeToken,
                    RequestOptions options, ApiMode apiMode)
                    throws com.stripe.exception.StripeException {
                throw new com.stripe.exception.ApiConnectionException(
                        "page-2 fetch failed (simulated outage)");
            }

            @Override
            public InputStream requestStream(BaseAddress baseAddress,
                    ApiResource.RequestMethod method, String path,
                    java.util.Map<String, Object> params, RequestOptions options,
                    ApiMode apiMode) {
                throw new UnsupportedOperationException("no stream requests in this test");
            }

            @Override
            public void validateRequestOptions(RequestOptions options) {
                // this test pins the FETCH-failure contract; the options
                // discipline is the round-9 test's own pin above
            }
        };
        firstPage.setResponseGetter(failingGetter);

        try (MockedStatic<com.stripe.model.Refund> refundMock = mockStatic(com.stripe.model.Refund.class)) {
            refundMock.when(() -> com.stripe.model.Refund.list(
                            any(RefundListParams.class), any(RequestOptions.class)))
                    .thenReturn(firstPage);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> channel.createRemoteRefund("pi_fx", 1500L, "op-key-fx"))
                    .isInstanceOf(PspChannelException.class)
                    .hasMessageContaining("pagination failed")
                    .hasMessageContaining("listing refunds for intent pi_fx")
                    .hasMessageContaining("page-2 fetch failed");

            // and the create NEVER ran — a refund is never created on an
            // incomplete scan
            refundMock.verify(() -> com.stripe.model.Refund.create(
                            any(RefundCreateParams.class), any(RequestOptions.class)),
                    org.mockito.Mockito.never());
        }
    }

    /** A Charge extracted through the channel's own tolerant pattern. */
    private static Charge chargeFromSignedEvent(String payload) {
        try {
            Event event = Webhook.constructEvent(payload,
                    Webhook.Signature.generateSignatureHeader(payload, SECRET), SECRET);
            var deserializer = event.getDataObjectDeserializer();
            if (deserializer.getObject().isPresent()
                    && deserializer.getObject().get() instanceof Charge charge) {
                return charge;
            }
            return (Charge) deserializer.deserializeUnsafe();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * CodeRabbit round 10 — the live cumulative read the refund.updated
     * settle path fetches ({@link StripePspChannel#readRemoteRefundTotalCents}):
     * the intent's charges through the documented list API (payment_intent
     * filter + auto-pagination), each contributing its OWN amount_refunded —
     * the provider's cumulative per charge, never a locally computed refund
     * ledger. Two charges on the intent (an original charge and its
     * re-authorization) sum their refunded totals.
     */
    @Test
    void readRemoteRefundTotalCents_sumsTheIntentChargesOwnCumulatives() {
        Charge first = chargeFromSignedEvent("{\"id\":\"evt_c1\",\"object\":\"event\","
                + "\"type\":\"charge.refunded\",\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"ch_c1\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_tot\",\"amount_refunded\":1000}}}");
        Charge second = chargeFromSignedEvent("{\"id\":\"evt_c2\",\"object\":\"event\","
                + "\"type\":\"charge.refunded\",\"livemode\":false,\"api_version\":\"2026-01-01\","
                + "\"data\":{\"object\":{\"id\":\"ch_c2\",\"object\":\"charge\","
                + "\"payment_intent\":\"pi_tot\",\"amount_refunded\":500}}}");
        com.stripe.model.ChargeCollection charges =
                Mockito.mock(com.stripe.model.ChargeCollection.class);
        Mockito.when(charges.autoPagingIterable()).thenReturn(java.util.List.of(first, second));

        try (MockedStatic<com.stripe.model.Charge> chargeMock = mockStatic(Charge.class)) {
            chargeMock.when(() -> Charge.list(
                            org.mockito.ArgumentMatchers.any(com.stripe.param.ChargeListParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(charges);

            Long total = channel.readRemoteRefundTotalCents("pi_tot");

            org.assertj.core.api.Assertions.assertThat(total).isEqualTo(1500L);
            chargeMock.verify(() -> Charge.list(
                            org.mockito.ArgumentMatchers.argThat(
                                    (com.stripe.param.ChargeListParams params) -> params != null
                                            && "pi_tot".equals(params.getPaymentIntent())),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)));
        }
    }

    /**
     * Round 10 — the null contract: an intent with no charge yet answers
     * {@code null} (nothing refundable, never an invented zero from an empty
     * page — the caller distinguishes "provider has no charge" from
     * "provider says nothing refunded").
     */
    @Test
    void readRemoteRefundTotalCents_intentWithoutCharges_answersNull() {
        com.stripe.model.ChargeCollection charges =
                Mockito.mock(com.stripe.model.ChargeCollection.class);
        Mockito.when(charges.autoPagingIterable()).thenReturn(java.util.List.of());

        try (MockedStatic<com.stripe.model.Charge> chargeMock = mockStatic(Charge.class)) {
            chargeMock.when(() -> Charge.list(
                            org.mockito.ArgumentMatchers.any(com.stripe.param.ChargeListParams.class),
                            org.mockito.ArgumentMatchers.any(RequestOptions.class)))
                    .thenReturn(charges);

            Long total = channel.readRemoteRefundTotalCents("pi_empty");

            org.assertj.core.api.Assertions.assertThat(total).isNull();
        }
    }
}
