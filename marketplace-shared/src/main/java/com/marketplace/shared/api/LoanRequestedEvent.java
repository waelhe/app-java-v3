package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): a borrowing request landed — the owner's
 * decision gate opens. The publisher is the lending module (its own
 * transaction); the named consumer is the notifications module's late-lander.
 */
public record LoanRequestedEvent(UUID loanId, UUID borrowerId, UUID ownerId) {
}
