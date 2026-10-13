package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the loan was cancelled before activation
 * — the payments module's listener settles the money (cancel the unpaid
 * intent, refund the collected one), the notifications listener writes the
 * borrower's receipt. The payload carries the committed truth (the
 * recipients are never derived in a listener — the plan's §8.1 rule).
 */
public record LoanCancelledEvent(UUID loanId, UUID borrowerId) {
}
