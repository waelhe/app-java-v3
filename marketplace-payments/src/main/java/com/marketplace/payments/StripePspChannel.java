package com.marketplace.payments;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;

/**
 * The Stripe implementation of the outbound payment channel — the ONLY class
 * in the codebase importing {@code com.stripe} (isolation rule of
 * {@code S3MediaStorage}). Official bits (artifact 33.4.1, evidence cached
 * under {@code scripts/psp-doc-verify/}, bytecode + official docs):
 * <ul>
 *   <li>{@code PaymentIntent.create(params, requestOptions)} with
 *       {@code RequestOptions.builder().setApiKey(..).setIdempotencyKey(..)}
 *       — per-call key material, never global mutable state.</li>
 *   <li>Amount model matches the house model exactly: "a positive integer
 *       ... in the smallest currency unit" — the local amountCents is passed
 *       through unchanged.</li>
 *   <li>Webhook verification is the SDK's own
 *       {@code Webhook.constructEvent(payload, sigHeader, secret)} with
 *       DEFAULT_TOLERANCE = 300 seconds — verified from the sources jar.</li>
 *   <li>Event data extraction follows the official javadoc pattern
 *       ("safe integration pattern"): {@code getObject()} when API versions
 *       match, {@code deserializeUnsafe()} as the documented fallback —
 *       webhook snapshots never change, so id/type/metadata are stable.</li>
 * </ul>
 *
 * <p>Test vs live mode is a property of the bound key (sk_test_... vs
 * sk_live_...) — the official sandbox channel; CI never binds any key, so
 * the channel stays inert in every pipeline run.
 *
 * <p>Not final: {@code payment.psp.create} is observed, so Spring builds a
 * CGLIB proxy of this bean when the channel is bound — CGLIB cannot subclass
 * final classes (live boot proof, this branch).
 */
class StripePspChannel implements PspChannel {

    private static final Logger log = LoggerFactory.getLogger(StripePspChannel.class);

    /** Metadata key carrying the marketplace intent id into the remote intent. */
    static final String MARKETPLACE_INTENT_ID = "marketplace_intent_id";

    private final String apiKey;
    private final String webhookSecret;

    StripePspChannel(String apiKey, String webhookSecret) {
        this.apiKey = apiKey;
        this.webhookSecret = webhookSecret;
    }

    @Override
    @Observed(name = "payment.psp.create")
    public RemoteIntent createRemoteIntent(UUID marketplaceIntentId, long amountCents, String currency,
                                           String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                // Locale.ROOT: ISO currency codes are ASCII — the default-locale
                // lowercase turns ILS into "ıls" under a Turkish JVM locale and
                // Stripe rejects the code (CodeRabbit #241; same rule as
                // Currencies.normalize / CurrencyExchangeProperties).
                .setCurrency(currency.toLowerCase(java.util.Locale.ROOT))
                .putMetadata(MARKETPLACE_INTENT_ID, marketplaceIntentId.toString())
                .build();
        RequestOptions options = RequestOptions.builder()
                .setApiKey(apiKey)
                .setIdempotencyKey(idempotencyKey)
                .build();
        try {
            PaymentIntent remote = PaymentIntent.create(params, options);
            return new RemoteIntent(remote.getId(), remote.getClientSecret());
        } catch (StripeException e) {
            throw new PspChannelException("Stripe PaymentIntent creation failed for marketplace intent "
                    + marketplaceIntentId + ": " + e.getMessage(), e);
        }
    }

    @Override
    @Observed(name = "payment.psp.refund")
    public RemoteRefund createRemoteRefund(String pspIntentId, Long amountCents, String idempotencyKey) {
        RefundCreateParams.Builder params = RefundCreateParams.builder()
                .setPaymentIntent(pspIntentId);
        if (amountCents != null) {
            params.setAmount(amountCents);
        }
        RequestOptions options = RequestOptions.builder()
                .setApiKey(apiKey)
                .setIdempotencyKey(idempotencyKey)
                .build();
        try {
            com.stripe.model.Refund refund = com.stripe.model.Refund.create(params.build(), options);
            // The cumulative remote actual lives on the charge the refund
            // landed on ("amount_refunded", Charges API) — the local books
            // must reflect the provider's number, not a local sum. A refund
            // created by payment intent always resolves to its charge; a
            // null charge id means the provider answered incoherently and
            // is surfaced loudly instead of being papered over.
            if (refund.getCharge() == null) {
                throw new PspChannelException("Stripe refund " + refund.getId()
                        + " for intent " + pspIntentId + " carried no charge id — cannot read the"
                        + " cumulative refunded amount");
            }
            Charge charge = Charge.retrieve(refund.getCharge(), RequestOptions.builder()
                    .setApiKey(apiKey).build());
            Long cumulative = charge.getAmountRefunded();
            return new RemoteRefund(refund.getId(), refund.getStatus(),
                    cumulative == null ? 0L : cumulative);
        } catch (StripeException e) {
            throw new PspChannelException("Stripe refund creation failed for intent "
                    + pspIntentId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public VerifiedWebhook verifyWebhook(String rawPayload, String signatureHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(rawPayload, signatureHeader, webhookSecret);
        } catch (Exception e) {
            // SignatureVerificationException (official doc: "at least one of the
            // three parameters ... is incorrect") or malformed JSON — rejected
            // as a unit, no partial state. Same exception family (and thus the
            // same 403 answer) as the legacy HMAC channel's "Invalid webhook
            // signature" — one contract for forged notifications, whichever
            // channel they arrive on.
            throw new AccessDeniedException("Stripe webhook signature verification failed: " + e.getMessage());
        }
        PaymentIntent data = extractPaymentIntent(event);
        if (data == null) {
            // charge.refunded events carry a Charge, not a PaymentIntent (L19):
            // the charge's payment_intent resolves the local intent through
            // the V33 link and its amount_refunded is the remote actual.
            Charge charge = extractCharge(event);
            if (charge != null) {
                Long cumulative = charge.getAmountRefunded();
                return new VerifiedWebhook(event.getId(), event.getType(), null,
                        charge.getPaymentIntent(),
                        new RefundSnapshot(cumulative == null ? 0L : cumulative));
            }
            // Events we did not subscribe to (or unknown objects) —
            // acknowledged and ignored by returning a no-op record; dispatch
            // treats unknown types as debug-level noise, exactly like the
            // legacy webhook contract.
            log.debug("Stripe event {} carried no payment intent object: {}", event.getId(), event.getType());
            return new VerifiedWebhook(event.getId(), event.getType(), null, null, null);
        }
        UUID marketplaceIntentId = metadataIntentId(data.getMetadata());
        return new VerifiedWebhook(event.getId(), event.getType(), marketplaceIntentId, data.getId(), null);
    }

    /**
     * Official extraction pattern (Event javadoc): version-safe
     * {@code getObject()} first, documented fallback
     * {@code deserializeUnsafe()} when API versions differ.
     */
    private PaymentIntent extractPaymentIntent(Event event) {
        var deserializer = event.getDataObjectDeserializer();
        var safe = deserializer.getObject();
        if (safe.isPresent() && safe.get() instanceof PaymentIntent intent) {
            return intent;
        }
        try {
            if (deserializer.deserializeUnsafe() instanceof PaymentIntent intent) {
                return intent;
            }
        } catch (EventDataObjectDeserializationException e) {
            log.warn("Stripe event {} data could not be deserialized: {}", event.getId(), e.getMessage());
        }
        return null;
    }

    /** Same official extraction pattern for Charge-carrying events (L19). */
    private Charge extractCharge(Event event) {
        var deserializer = event.getDataObjectDeserializer();
        var safe = deserializer.getObject();
        if (safe.isPresent() && safe.get() instanceof Charge charge) {
            return charge;
        }
        try {
            if (deserializer.deserializeUnsafe() instanceof Charge charge) {
                return charge;
            }
        } catch (EventDataObjectDeserializationException e) {
            log.warn("Stripe event {} data could not be deserialized: {}", event.getId(), e.getMessage());
        }
        return null;
    }

    private UUID metadataIntentId(Map<String, String> metadata) {
        if (metadata == null) {
            return null;
        }
        String raw = metadata.get(MARKETPLACE_INTENT_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.warn("Stripe metadata carried a non-UUID marketplace intent id: {}", raw);
            return null;
        }
    }
}
