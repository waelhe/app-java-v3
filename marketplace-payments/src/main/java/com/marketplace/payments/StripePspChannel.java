package com.marketplace.payments;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.RefundCollection;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.ChargeRetrieveParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Metadata key carrying the refund operation's idempotency key into the
     * remote refund (CodeRabbit round 2 on #828): the charge.refunded webhook
     * reads it back to settle the journal by identity.
     */
    static final String OPERATION_KEY_METADATA = "marketplace_refund_operation_key";

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
        // CodeRabbit round 2 on #828: the operation key rides the refund as
        // provider metadata (the documented metadata mechanism — "attach
        // key-value data" to any Stripe object) so the charge.refunded
        // webhook can settle THIS operation's journal record by identity.
        // The Idempotency-Key header alone is not echoed back in webhook
        // payloads; metadata is.
        params.putMetadata(OPERATION_KEY_METADATA, idempotencyKey);
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
                // The identities are resolved only for a USABLE event (one
                // carrying the payment_intent the dispatch resolves); an
                // unusable one never reads its snapshot, so no retrieval
                // fires for it.
                RefundSnapshot snapshot = charge.getPaymentIntent() == null
                        ? new RefundSnapshot(cumulative == null ? 0L : cumulative)
                        : new RefundSnapshot(cumulative == null ? 0L : cumulative,
                                executedRefunds(charge));
                return new VerifiedWebhook(event.getId(), event.getType(), null,
                        charge.getPaymentIntent(), snapshot);
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

    /**
     * The charge's completed refunds (CodeRabbit round 2 on #828): each
     * succeeded refund entry with the operation key this service stamped as
     * its metadata. Refunds without the key (created directly at the
     * provider) carry a null key and stay books-sync only.
     *
     * <p>CodeRabbit round 3: the refunds collection is an expandable list and
     * a charge.refunded payload may omit it — answering an EMPTY list there
     * would let the books sync to the cumulative without the identity settle,
     * recreating the very stuck window this closes. On omission (or a
     * truncated embedded page) the charge is re-retrieved with the documented
     * expand mechanism (ChargeRetrieveParams.addExpand("refunds")) and the
     * retrieved collection rides the documented auto-pagination with explicit
     * request options. A webhook-deserialized collection is iterated from its
     * embedded data only — it carries no response getter, and its
     * auto-pagination would throw (measured in the 33.4.2 sources:
     * StripeCollection.autoPagingIterable dereferences the transient
     * responseGetter only API responses attach).</p>
     */
    private List<PspChannel.ExecutedRefund> executedRefunds(Charge charge) {
        RefundCollection refunds = charge.getRefunds();
        boolean embeddedComplete = refunds != null && refunds.getData() != null
                && !Boolean.TRUE.equals(refunds.getHasMore());
        if (!embeddedComplete && charge.getId() != null) {
            refunds = retrieveExpandedRefunds(charge.getId());
            embeddedComplete = refunds != null && refunds.getData() != null
                    && !Boolean.TRUE.equals(refunds.getHasMore());
        }
        if (refunds == null || refunds.getData() == null) {
            return List.of();
        }
        if (embeddedComplete) {
            return mapExecutedRefunds(refunds.getData());
        }
        // the retrieved, multi-page collection: the documented auto-pagination
        List<PspChannel.ExecutedRefund> executed = new ArrayList<>();
        for (com.stripe.model.Refund refund : refunds.autoPagingIterable(java.util.Map.of(),
                RequestOptions.builder().setApiKey(apiKey).build())) {
            if ("succeeded".equals(refund.getStatus())) {
                executed.add(toExecutedRefund(refund));
            }
        }
        return List.copyOf(executed);
    }

    private List<PspChannel.ExecutedRefund> mapExecutedRefunds(List<com.stripe.model.Refund> refunds) {
        return refunds.stream()
                .filter(refund -> "succeeded".equals(refund.getStatus()))
                .map(StripePspChannel::toExecutedRefund)
                .toList();
    }

    private static PspChannel.ExecutedRefund toExecutedRefund(com.stripe.model.Refund refund) {
        return new PspChannel.ExecutedRefund(refund.getId(),
                refund.getMetadata() == null ? null
                        : refund.getMetadata().get(OPERATION_KEY_METADATA));
    }

    /**
     * The documented expansion retrieval for a charge whose webhook payload
     * omitted the refunds collection — the same API surface
     * {@code createRemoteRefund} already uses for the cumulative read.
     */
    private RefundCollection retrieveExpandedRefunds(String chargeId) {
        try {
            Charge retrieved = Charge.retrieve(chargeId,
                    ChargeRetrieveParams.builder().addExpand("refunds").build(),
                    RequestOptions.builder().setApiKey(apiKey).build());
            return retrieved.getRefunds();
        } catch (StripeException e) {
            throw new PspChannelException("Stripe charge retrieval for the refund identities failed (charge "
                    + chargeId + "): " + e.getMessage(), e);
        }
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
