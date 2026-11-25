package com.marketplace.shared.api;

import java.util.UUID;

/**
 * ADR-0009 (plan D-09 closure): the loan entered the DISPUTED state — the
 * freeze is real. Published by the lending module's dispute listener when
 * a {@link DisputeOpenedEvent} (LOAN subject) lands; the notifications
 * module writes both parties' receipts (the loan legs' listener twin).
 *
 * <p>This is the event ADR-0004's loan javadoc already promised ("the
 * loan-side state and the event exist now") — the ADR-0009 closure makes
 * the promise true in code, not prose.
 */
// CodeRabbit round on 2d1081f8 (R2): the dispute id rides the event — the
// freeze CYCLE's identity (a loan can be disputed again after a resume; the
// notifications delivery key deduplicates per cycle, not per loan).
public record LoanDisputedEvent(UUID disputeId, UUID loanId, UUID borrowerId, UUID ownerId) {
}
