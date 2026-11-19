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

    /**
     * Reads the charge's CURRENT cumulative refunded amount through the
     * provider's own API — the same authoritative cumulative read
     * {@code createRemoteRefund} answers with (the retrieved
     * {@code charge.amount_refunded}), not a sum the caller computes.
     *
     * <p><b>CodeRabbit round 10 on #828 (the refund.updated settle):</b> the
     * {@code refund.updated}/{@code refund.created} identity vehicle carries
     * NO cumulative (a Refund object knows its own amount, never the
     * charge's total), and Stripe does not guarantee webhook delivery order
     * (official webhooks documentation) — the identity event can arrive
     * BEFORE the {@code charge.refunded} event whose cumulative would sync
     * the books. Settling the journal EXECUTED on that arrival therefore
     * needs the remote total fetched live from the provider before the
     * caller's reconciliation answer reads the local books; otherwise an
     * independent earlier refund satisfies the books' arithmetic alone
     * while this operation's own money is missing from the synced total.
     * Returns {@code null} when no charge-backed refund total is
     * resolvable for the intent (never invented).</p>
     */
    Long readRemoteRefundTotalCents(String pspIntentId);

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
     *
     * <p><b>CodeRabbit round 2 on #828 (the refund-specific execution
     * evidence):</b> the snapshot also carries the charge's refund entries
     * that completed at the provider. A refund this service created carries
     * the refund operation's idempotency key as its provider metadata (see
     * {@code StripePspChannel.OPERATION_KEY_METADATA}), so the webhook — the
     * only witness of the provider's own refund record — can settle the
     * matching journal operation by IDENTITY, never by the books'
     * cumulative arithmetic (an independent refund advances the cumulative
     * just as well while this operation never sent). Refunds created outside
     * this service (the dashboard) carry no operation key and stay
     * books-sync only.</p>
     */
    record RefundSnapshot(long refundedAmountCents, java.util.List<ExecutedRefund> executedRefunds) {

        /**
         * Books-sync only: no refund identities carried (legacy callers).
         * CodeRabbit round 15 (2026-11-19T14:40Z review): stays PUBLIC —
         * the record's implicit canonical constructor always was, and this
         * convenience twin must keep the same reach for out-of-package
         * callers assembling the books-sync shape.
         */
        public RefundSnapshot(long refundedAmountCents) {
            this(refundedAmountCents, java.util.List.of());
        }
    }

    /**
     * A refund the provider reports as completed on the charge, with the
     * refund operation key this service stamped as its metadata (null when
     * the refund was not created through this service's contract).
     */
    record ExecutedRefund(String refundId, String operationKey) {}

    /**
     * Verified webhook fields mapped onto the house dispatch contract. For
     * {@code charge.refunded} events the {@code pspIntentId} carries the
     * charge's payment intent id (resolution then flows through the V33
     * {@code findByPspIntentId} link) and {@code refund} carries the
     * snapshot — the ONE event whose {@code refundedAmountCents} is the
     * financial cumulative total the books sync to.
     *
     * <p><b>CodeRabbit round 13 follow-up (the refund.* identity-only
     * snapshot):</b> {@code refund.created} and {@code refund.updated}
     * events ALSO carry a {@code refund} snapshot — but an IDENTITY-ONLY
     * one: its {@code refundedAmountCents} is the literal {@code 0L} the
     * channel answers for these events and is NOT a financial total (the
     * Refund object these events carry has no charge cumulative). Consumers
     * read the refund IDENTITIES (the operation keys) from these snapshots
     * to settle the journal — never the amount; the cumulative total rides
     * the {@code charge.refunded} snapshot alone (see
     * {@link StripePspChannel#verifyWebhook}). For every other event type
     * {@code refund} is null.</p>
     */
    record VerifiedWebhook(String eventId, String eventType, UUID marketplaceIntentId,
                           String pspIntentId, RefundSnapshot refund) {}
}
