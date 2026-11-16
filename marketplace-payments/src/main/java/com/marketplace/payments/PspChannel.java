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
     * Creates a remote refund on a previously charged payment intent (L19 —
     * the closed money loop). Official contract: "you must specify a Charge
     * or a PaymentIntent object on which to create it ... You can optionally
     * refund only part of a charge. You can do so multiple times, until the
     * entire charge has been refunded" (Refunds / Create a refund).
     *
     * @param pspIntentId    the remote intent id ({@code pi_...}) to refund
     * @param amountCents    partial amount in the smallest currency unit,
     *                       or {@code null} for the remaining (full) amount
     * @param idempotencyKey deterministic replay key — a retried request
     *                       replays the SAME remote refund instead of
     *                       double-refunding
     * @return the refund id, its remote status and the CUMULATIVE amount
     *         refunded on the underlying charge after this refund — the
     *         remote actual the local books must reflect (roadmap L19
     *         acceptance 3), not a locally computed sum
     */
    RemoteRefund createRemoteRefund(String pspIntentId, Long amountCents, String idempotencyKey);

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

    /** Remote refund outcome: id, provider status, cumulative remote total. */
    record RemoteRefund(String refundId, String status, long refundedTotalCents) {}

    /**
     * Refund state carried by a verified {@code charge.refunded} webhook
     * (L19): the cumulative refunded amount on the charge, taken from the
     * provider's own payload — the async safety net that syncs local state
     * for refunds completed outside this service (including the Stripe
     * dashboard).
     */
    record RefundSnapshot(long refundedAmountCents) {}

    /**
     * Verified webhook fields mapped onto the house dispatch contract. For
     * {@code charge.refunded} events the {@code pspIntentId} carries the
     * charge's payment intent id (resolution then flows through the V33
     * {@code findByPspIntentId} link) and {@code refund} carries the
     * snapshot; otherwise {@code refund} is null.
     */
    record VerifiedWebhook(String eventId, String eventType, UUID marketplaceIntentId,
                           String pspIntentId, RefundSnapshot refund) {}
}
