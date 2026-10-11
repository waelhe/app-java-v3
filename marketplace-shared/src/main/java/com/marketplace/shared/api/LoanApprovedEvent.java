package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the owner accepted the loan — the
 * borrower's payment gate opens (the intent rides the existing engine).
 */
public record LoanApprovedEvent(UUID loanId, UUID borrowerId, UUID ownerId) {
}
