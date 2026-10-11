package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The payment intent's cross-module read model. W5 (yelp-level plan §5 —
 * the ads & billing wave) widened it with the origin vocabulary, the
 * campaign pairing and the money pair: the ledger's completion listener
 * needs all three to book an AD-origin settlement (the raw amount credit
 * — no commission, no booking lookup), and the notification listener
 * names the campaign in the ad bill's message — which is why they ride
 * the port instead of a second lookup.
 *
 * <p>{@code origin} is the plan's §4.2 explicit shape: a plain string
 * held by the database CHECK ({@code chk_payment_intents_origin}), not a
 * Java enum — the same decision W1's review origin made. The constants
 * live on this record so every consumer reads one vocabulary.
 *
 * @param paymentIntentId the intent's id
 * @param bookingId       null iff origin = AD (the cross-column CHECK
 *                        {@code ck_payment_intents_origin_pairing} pins
 *                        the pairing)
 * @param consumerId      the payer — the booking's consumer, or the
 *                        provider for an ad bill
 * @param adCampaignId    the billed campaign — null iff origin is BOOKING
 * @param status          the intent's current state name
 * @param origin          BOOKING (the whole pre-W5 table) or AD
 * @param amountCents     the money the intent collects
 * @param currency        the money's ISO 4217 code
 */
public record PaymentIntentDetails(
        UUID paymentIntentId,
        UUID bookingId,
        UUID consumerId,
        UUID adCampaignId,
        String status,
        String origin,
        long amountCents,
        String currency,
        UUID orderId,
        UUID loanId
) {

    /** The verified path's origin — the whole pre-W5 table. */
    public static final String ORIGIN_BOOKING = "BOOKING";

    /** W5's ad-bill origin — the payer is the provider, no booking. */
    public static final String ORIGIN_AD = "AD";

    /** Stage 6 (ADR-0002): the order-checkout origin — the payer is the buyer, the subject is the order. */
    public static final String ORIGIN_ORDER = "ORDER";

    /** Stage 8 (ADR-0004): the loan-fee origin — the payer is the borrower, the subject is the loan. */
    public static final String ORIGIN_LOAN = "LOAN";

    public boolean isAdOrigin() {
        return ORIGIN_AD.equals(origin);
    }

    /** Stage 6 (ADR-0002): the order-checkout settlement branch's own test. */
    public boolean isOrderOrigin() {
        return ORIGIN_ORDER.equals(origin);
    }

    /** Stage 8 (ADR-0004): the loan-fee settlement branch's own test. */
    public boolean isLoanOrigin() {
        return ORIGIN_LOAN.equals(origin);
    }
}
