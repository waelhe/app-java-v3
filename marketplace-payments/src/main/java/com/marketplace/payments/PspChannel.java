package com.marketplace.payments;

import java.util.UUID;

/**
 * The outbound payment channel of the marketplace (roadmap B3 / gap
 * G-PROD-3). The intent system, webhook log, ledger and refund flows are the
 * house's own design; this port is the single seam where a real Payment
 * Service Provider plugs in. The Stripe implementation
 * ({@link StripePspChannel}) is the only class in the codebase allowed to
 * import {@code com.stripe} — same isolation rule as {@code S3MediaStorage}
 * for the AWS SDK.
 *
 * <p>Provider choice is a deployment decision, exactly like the MAIL and
 * MEDIA_S3 gates: when no credentials are bound, no channel beans exist
 * (see {@link StripeChannelConfiguredCondition}) and {@code processIntent}
 * keeps running the existing internal-intent behavior byte-for-byte. The
 * capability is OFF, not broken.
 *
 * <p>Idempotency: callers pass a deterministic key (derived from the local
 * intent id). Retries of the same request replay the same remote intent —
 * the official idempotency-key contract ("save successful responses ... so
 * that a certain request is never processed twice", Stripe API reference,
 * cached at {@code scripts/psp-doc-verify/stripe-idempotency.txt}).
 */
public interface PspChannel {

    /**
     * Creates the remote PaymentIntent for a local marketplace intent.
     *
     * @param marketplaceIntentId local intent id, embedded in the remote
     *                            intent metadata so webhooks can resolve it
     * @param amountCents         amount in the currency's smallest unit
     *                            (official model: "A positive integer ...
     *                            in the smallest currency unit")
     * @param currency            ISO 4217 code, e.g. "SAR"
     * @param idempotencyKey      deterministic replay key
     */
    RemoteIntent createRemoteIntent(UUID marketplaceIntentId, long amountCents, String currency,
                                    String idempotencyKey);

    /**
     * Verifies a raw webhook notification with the provider's own signature
     * scheme and extracts the fields the dispatch contract needs. Throws
     * {@link PspChannelException} when verification fails — the official
     * error ("Webhook signature verification failed") means at least one of
     * payload, signature or secret is wrong, and such a notification is
     * rejected without state change.
     */
    VerifiedWebhook verifyWebhook(String rawPayload, String signatureHeader);

    /** Remote intent identifiers handed back to the calling client. */
    record RemoteIntent(String pspIntentId, String clientSecret) {}

    /** Verified webhook fields mapped onto the house dispatch contract. */
    record VerifiedWebhook(String eventId, String eventType, UUID marketplaceIntentId,
                           String pspIntentId) {}
}
