package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 6 (plan D-08, ADR-0002): the order-keyed payment seam — the EXISTING
 * payment engine (Stripe channel, webhook inbox, idempotency, refunds)
 * reached through a module contract, so the orders module never imports the
 * payments module (the house port discipline verbatim — the
 * {@code PaymentRefundPort} seam shape).
 *
 * <p>The seam carries exactly the WRITE the machine's HTTP surface needs —
 * intent creation. The cancellation's money half is event-driven by design
 * (the {@code OrderCancelledEvent} → the payments module's own listener →
 * the ONE refund contract): one behavior for one financial operation, no
 * dual paths, and no dead port methods.
 */
public interface OrderPaymentPort {

    /**
     * Creates (or idempotently returns) the order's payment intent.
     *
     * @param orderId     the order the intent pays for
     * @param consumerId  the paying buyer
     * @param amountMinor the order total in minor units (the frozen lines' sum)
     * @param currency    the ISO-4217 code of the frozen lines
     * @return the engine's own intent details carrier
     */
    PaymentIntentDetails createForOrder(UUID orderId, UUID consumerId,
                                        long amountMinor, String currency);
}
