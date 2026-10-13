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
public record LoanDisputedEvent(UUID loanId, UUID borrowerId, UUID ownerId) {
}
