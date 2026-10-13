package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the loan's lifecycle completed — the
 * period is released for rebooking and the owner's settlement record is
 * complete (the money moved at payment time; this is the lifecycle's
 * terminal fact).
 */
public record LoanClosedEvent(UUID loanId, UUID borrowerId, UUID ownerId) {
}
