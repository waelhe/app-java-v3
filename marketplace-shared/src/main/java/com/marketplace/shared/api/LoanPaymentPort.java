package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the loan-keyed payment seam — the
 * EXISTING payment engine reached through a module contract (the
 * {@code OrderPaymentPort} twin verbatim). One intent per loan, the
 * deterministic {@code loan-…} idempotency key; the cancellation's money
 * half is event-driven (the {@code LoanCancelledEvent} → the payments
 * module's own listener → the ONE refund contract).
 */
public interface LoanPaymentPort {

    /**
     * Creates (or idempotently returns) the loan's payment intent.
     *
     * @param loanId      the loan the intent pays for
     * @param borrowerId  the paying borrower
     * @param amountMinor the fee in minor units (the owner's own terms)
     * @param currency    the ISO-4217 code of the fee
     * @return the engine's own intent details carrier
     */
    PaymentIntentDetails createForLoan(UUID loanId, UUID borrowerId,
                                       long amountMinor, String currency);
}
