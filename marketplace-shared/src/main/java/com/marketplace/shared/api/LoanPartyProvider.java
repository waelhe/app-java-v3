package com.marketplace.shared.api;

import java.util.UUID;

/**
 * ADR-0009 (plan D-09 closure): the port that lets the disputes module look
 * up loan party information without depending on the lending module — the
 * {@code BookingParticipantProvider} twin verbatim, and the concrete
 * mechanism ADR-0004 named "the {@code DisputeOpenPort} seam": the
 * generalized subject rides the module contract pair (this provider plus
 * the shared dispute events), the same shape the booking path has used
 * since V20.
 *
 * <p><b>Design decision — synchronous interface vs. event:</b> the same
 * reasoning as the booking twin's own javadoc — the caller (the disputes
 * module's open/list surfaces) needs the party facts <em>before</em>
 * persisting or listing, so a synchronous query is the official shape.
 *
 * @see BookingParticipantProvider
 */
public interface LoanPartyProvider {

    /**
     * Returns the loan's party information.
     *
     * @throws ResourceNotFoundException if the loan does not exist
     */
    LoanInfo getLoanInfo(UUID loanId);
}
