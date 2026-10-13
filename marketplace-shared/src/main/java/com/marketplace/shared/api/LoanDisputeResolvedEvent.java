package com.marketplace.shared.api;

import java.util.UUID;

/**
 * ADR-0009 (plan D-09 closure): the loan's dispute resolved without a
 * cancellation — the freeze released and the loan resumed its ACTIVE edge.
 * (A REFUND_CONSUMER resolution terminates the loan instead, so the
 * existing {@link LoanCancelledEvent} carries that outcome — one event per
 * fact, no doubles.)
 *
 * <p>Published by the lending module's dispute listener; the notifications
 * module writes both parties' receipts.
 */
public record LoanDisputeResolvedEvent(UUID loanId, UUID borrowerId, UUID ownerId) {
}
