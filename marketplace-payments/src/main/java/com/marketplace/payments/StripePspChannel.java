package com.marketplace.payments;

import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.ChargeCollection;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.RefundCollection;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.ChargeListParams;
import com.stripe.param.ChargeRetrieveParams;
import com.stripe.param.RefundListParams;
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
        // CodeRabbit round 4 (reconcile-before-create): the provider's
        // idempotency records expire after 24 hours — a retry of the SAME
        // operation past that window would otherwise CREATE a second refund
        // for it. A refund already carrying THIS operation's key in its
        // metadata IS the operation's refund: it is returned as-is (its own
        // status, its charge's cumulative), never re-created. The idempotency
        // key itself is unchanged — the reconcile is the safety net AROUND
        // the key's expiry, never a replacement for it.
        com.stripe.model.Refund existing = findRefundByOperationKey(pspIntentId, idempotencyKey);
        if (existing != null) {
            return toRemoteRefund(existing);
        }
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
            return toRemoteRefund(refund);
        } catch (StripeException e) {
            throw new PspChannelException("Stripe refund creation failed for intent "
                    + pspIntentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * CodeRabbit round 13 (unified pagination-error contract): the SDK's
     * {@code PagingIterator.next()} (v33.4.2 sources, line 58) wraps EVERY
     * failed page fetch — {@code StripeException} included — in a raw
     * {@code RuntimeException("Unable to lazy-load stripe objects", e)}
     * that would escape this channel's {@code PspChannelException} contract.
     * Every auto-pagination loop routes its iterable through this helper so
     * a page failure mid-scan surfaces as {@code PspChannelException} with
     * the cause preserved — never a partial total, never a refund created
     * on an incomplete scan, never a foreign RuntimeException at the
     * caller's retry boundary.
     */
    private static <T> Iterable<T> safePagination(Iterable<T> pages, String what) {
        return () -> new java.util.Iterator<>() {
            private final java.util.Iterator<T> delegate = pages.iterator();

            @Override
            public boolean hasNext() {
                try {
                    return delegate.hasNext();
                } catch (RuntimeException e) {
                    throw paged(e, what);
                }
            }

            @Override
            public T next() {
                try {
                    return delegate.next();
                } catch (RuntimeException e) {
                    throw paged(e, what);
                }
            }
        };
    }

    /**
     * The wrap itself: unwrap the SDK's own RuntimeException envelope down
     * to its {@code StripeException} cause when present (the same channel
     * contract the direct API calls use), and wrap anything else as the
     * channel's own exception with the cause preserved.
     */
    private static PspChannelException paged(RuntimeException e, String what) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return new PspChannelException("Stripe pagination failed while " + what + ": "
                + cause.getMessage(), cause);
    }

    /**
     * The operation's existing refund, matched by its provider metadata —
     * null when none exists. The list is filtered by the payment intent and
     * rides the documented auto-pagination. A matching refund whose provider
     * status is {@code failed} or {@code canceled} is NOT the operation's
     * money (CodeRabbit round 5): the scan continues — across the remaining
     * pages — and only a non-failed match (succeeded, pending, or any other
     * live status) is returned as the operation's refund; a fresh one is
     * created when every match under the key is dead.
     */
    private com.stripe.model.Refund findRefundByOperationKey(String pspIntentId, String operationKey) {
        try {
            RefundCollection refunds = com.stripe.model.Refund.list(
                    RefundListParams.builder().setPaymentIntent(pspIntentId).setLimit(100L).build(),
                    RequestOptions.builder().setApiKey(apiKey).build());
            // CodeRabbit round 8: the no-arg auto-pagination keeps the ORIGINAL
            // list request's params (payment_intent, limit) and options on
            // every subsequent page fetch — an explicit params map would
            // RESET them and fetch unfiltered pages. The collection came from
            // the API response, so its response getter is attached.
            for (com.stripe.model.Refund refund : safePagination(
                    refunds.autoPagingIterable(), "listing refunds for intent " + pspIntentId)) {
                if (!operationKey.equals(refund.getMetadata() == null
                        ? null : refund.getMetadata().get(OPERATION_KEY_METADATA))) {
                    continue;
                }
                if ("failed".equals(refund.getStatus()) || "canceled".equals(refund.getStatus())) {
                    log.info("Stripe refund {} carries operation key {} but is {} — not the "
                                    + "operation's money; the reconcile scan continues",
                            refund.getId(), operationKey, refund.getStatus());
                    continue;
                }
                return refund;
            }
            return null;
        } catch (StripeException e) {
            throw new PspChannelException("Stripe refund listing failed for intent "
                    + pspIntentId + ": " + e.getMessage(), e);
        }
    }

    /**
     * The remote-refund answer for an existing refund object: its own status
     * and its charge's cumulative — the same shape the create path answers
     * (the cumulative read the local books follow, L19).
     */
    private RemoteRefund toRemoteRefund(com.stripe.model.Refund refund) {
        // A refund created by payment intent always resolves to its charge; a
        // null charge id means the provider answered incoherently and is
        // surfaced loudly instead of being papered over.
        if (refund.getCharge() == null) {
            throw new PspChannelException("Stripe refund " + refund.getId()
                    + " carried no charge id — cannot read the cumulative refunded amount");
        }
        try {
            Charge charge = Charge.retrieve(refund.getCharge(),
                    RequestOptions.builder().setApiKey(apiKey).build());
            Long cumulative = charge.getAmountRefunded();
            return new RemoteRefund(refund.getId(), refund.getStatus(),
                    cumulative == null ? 0L : cumulative);
        } catch (StripeException e) {
            throw new PspChannelException("Stripe charge retrieval failed for refund "
                    + refund.getId() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Round 10 — the live cumulative read the refund.updated settle needs
     * ({@link PspChannel#readRemoteRefundTotalCents}): the intent's charges,
     * each with its own {@code amount_refunded}, taken from the provider's
     * own API — the same authoritative Charge retrieval
     * {@code toRemoteRefund} performs for a single refund. The sum over the
     * intent's charges is the provider's own model ("A PaymentIntent ...
     * tracks the charge"), never a locally computed refund ledger. Returns
     * {@code null} when the intent has no charge yet (nothing refunded, and
     * nothing this contract may invent).
     */
    @Override
    public Long readRemoteRefundTotalCents(String pspIntentId) {
        try {
            ChargeCollection charges = Charge.list(
                    ChargeListParams.builder().setPaymentIntent(pspIntentId).setLimit(100L).build(),
                    RequestOptions.builder().setApiKey(apiKey).build());
            long cumulative = 0L;
            boolean anyCharge = false;
            // Round 13: the pagination rides the unified error contract — a
            // mid-scan page failure never answers a PARTIAL cumulative (the
            // books-sync grounds reconciliation answers on this number).
            for (Charge charge : safePagination(charges.autoPagingIterable(),
                    "listing charges for the refund total read (intent " + pspIntentId + ")")) {
                anyCharge = true;
                if (charge.getAmountRefunded() != null) {
                    cumulative += charge.getAmountRefunded();
                }
            }
            return anyCharge ? cumulative : null;
        } catch (StripeException e) {
            throw new PspChannelException("Stripe charge listing failed for the refund total read (intent "
                    + pspIntentId + "): " + e.getMessage(), e);
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
        // CodeRabbit round 6: refund.* events carry the Refund object itself
        // — the identity vehicle for a refund that SUCCEEDED asynchronously
        // after its pending creation (charge.refunded may have already fired
        // on the pending state, or the refunds list it carried predates the
        // success). The books stay with charge.refunded's cumulative sync;
        // this path settles the identity only. Whether refund.updated is
        // DELIVERED depends on the webhook endpoint's event subscription
        // (an owner dashboard setting) — the code handles the event when it
        // arrives and assumes nothing about the subscription.
        if ("refund.updated".equals(event.getType()) || "refund.created".equals(event.getType())) {
            com.stripe.model.Refund refund = extractRefund(event);
            if (refund != null) {
                return new VerifiedWebhook(event.getId(), event.getType(), null,
                        refund.getPaymentIntent(),
                        new RefundSnapshot(0L, "succeeded".equals(refund.getStatus())
                                ? List.of(toExecutedRefund(refund)) : List.of()));
            }
            log.debug("Stripe refund event {} carried no refund object", event.getId());
            return new VerifiedWebhook(event.getId(), event.getType(), null, null, null);
        }
        PaymentIntent data = extractPaymentIntent(event);
        if (data == null) {
            // charge.refunded events carry a Charge, not a PaymentIntent (L19):
            // the charge's payment_intent resolves the local intent through
            // the V33 link and its amount_refunded is the remote actual.
            Charge charge = extractCharge(event);
            if (charge != null) {
                // CodeRabbit round 13: the refund identities are fetched ONLY
                // for the one event type the dispatch consumes them under —
                // charge.refunded. Every other charge-carrying event (e.g.
                // charge.succeeded, charge.failed) answers its snapshot from
                // the payload alone (cumulative only, no identity retrieval,
                // no refunds fetch); the ignore path for unsubscribed events
                // below stays exactly as it was.
                boolean wantsIdentities = "charge.refunded".equals(event.getType());
                // The identities are resolved only for a USABLE event (one
                // carrying the payment_intent the dispatch resolves); an
                // unusable one never reads its snapshot, so no retrieval
                // fires for it. When the payload omits the refunds
                // collection the charge is re-retrieved — and the RETRIEVED
                // cumulative rides along with the identities (CodeRabbit
                // round 4): it is the fresher number from the same API
                // snapshot the identities came from, never the event's stale
                // one.
                RefundIdentities identities = charge.getPaymentIntent() == null || !wantsIdentities
                        ? new RefundIdentities(
                                charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded(),
                                List.of())
                        : refundIdentities(charge);
                return new VerifiedWebhook(event.getId(), event.getType(), null,
                        charge.getPaymentIntent(),
                        new RefundSnapshot(identities.cumulativeRefundedCents(),
                                identities.executedRefunds()));
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

    /** Same official extraction pattern for Refund-carrying events (round 6). */
    private com.stripe.model.Refund extractRefund(Event event) {
        var deserializer = event.getDataObjectDeserializer();
        var safe = deserializer.getObject();
        if (safe.isPresent() && safe.get() instanceof com.stripe.model.Refund refund) {
            return refund;
        }
        try {
            if (deserializer.deserializeUnsafe() instanceof com.stripe.model.Refund refund) {
                return refund;
            }
        } catch (EventDataObjectDeserializationException e) {
            log.warn("Stripe event {} data could not be deserialized: {}", event.getId(), e.getMessage());
        }
        return null;
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
     * retrieved collection rides the documented auto-pagination with EXPLICIT
     * channel-key request options (CodeRabbit round 9): the nested refunds
     * collection inherits the response getter from the retrieval but NEVER
     * the request options — the SDK attaches those only to top-level
     * collections — so the no-arg overload would throw
     * ApiKeyMissingException under the per-channel key model (no global
     * Stripe key is ever set). A webhook-deserialized collection is iterated
     * from its embedded data only — it carries no response getter, and its
     * auto-pagination would throw (measured in the 33.4.2 sources:
     * StripeCollection.autoPagingIterable dereferences the transient
     * responseGetter only API responses attach).</p>
     *
     * <p>CodeRabbit round 4: when the retrieval fires, the RETRIEVED charge's
     * cumulative is the answer — the fresher number from the same API
     * snapshot the identities came from. The event's own cumulative is the
     * pre-retrieval baseline only.</p>
     */
    private RefundIdentities refundIdentities(Charge charge) {
        long cumulative = charge.getAmountRefunded() == null ? 0L : charge.getAmountRefunded();
        RefundCollection refunds = charge.getRefunds();
        boolean embeddedComplete = refunds != null && refunds.getData() != null
                && !Boolean.TRUE.equals(refunds.getHasMore());
        if (!embeddedComplete && charge.getId() != null) {
            Charge retrieved = retrieveExpandedRefunds(charge.getId());
            if (retrieved.getRefunds() != null && retrieved.getRefunds().getData() != null) {
                refunds = retrieved.getRefunds();
                if (retrieved.getAmountRefunded() != null) {
                    cumulative = retrieved.getAmountRefunded();
                }
                embeddedComplete = !Boolean.TRUE.equals(refunds.getHasMore());
            }
        }
        if (refunds == null || refunds.getData() == null) {
            return new RefundIdentities(cumulative, List.of());
        }
        if (embeddedComplete) {
            return new RefundIdentities(cumulative, mapExecutedRefunds(refunds.getData()));
        }
        // The retrieved, multi-page NESTED collection: the SDK attaches
        // request options ONLY to top-level collections (measured in the
        // 33.4.2 sources: LiveStripeResponseGetter sets them on the response
        // resource itself, and Charge.setResponseGetter propagates only the
        // response getter into the nested refunds) — this collection's own
        // options are null, so the no-arg auto-pagination would validate
        // null against the response getter's global default and throw
        // ApiKeyMissingException under the per-channel key model. The
        // explicit-options overload validates AND attaches the channel key,
        // so every subsequent page fetch rides it; the empty params override
        // loses nothing — the collection's own URL carries the charge
        // filter, and a nested collection never had request params
        // (CodeRabbit round 9; contrast findRefundByOperationKey, whose
        // TOP-LEVEL Refund.list collection carries its options and params,
        // so the no-arg overload is the correct call there).
        List<PspChannel.ExecutedRefund> executed = new ArrayList<>();
        // Round 13: the nested multi-page collection rides the SAME unified
        // pagination-error contract (a page failure here would strand a
        // half-settled identity list outside PspChannelException).
        for (com.stripe.model.Refund refund : safePagination(
                refunds.autoPagingIterable(Map.of(), RequestOptions.builder().setApiKey(apiKey).build()),
                "listing the charge's refunds")) {
            if ("succeeded".equals(refund.getStatus())) {
                executed.add(toExecutedRefund(refund));
            }
        }
        return new RefundIdentities(cumulative, List.copyOf(executed));
    }

    /** The cumulative and the identities, from ONE snapshot. */
    private record RefundIdentities(long cumulativeRefundedCents,
                                    List<PspChannel.ExecutedRefund> executedRefunds) {
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
    private Charge retrieveExpandedRefunds(String chargeId) {
        try {
            return Charge.retrieve(chargeId,
                    ChargeRetrieveParams.builder().addExpand("refunds").build(),
                    RequestOptions.builder().setApiKey(apiKey).build());
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
