package com.marketplace.shared.api;

import java.util.UUID;

/**
 * A dispute entered the pipeline (B-06 introduced it; ADR-0009 moved it to
 * {@code shared/api} — the parallel contracts ledger's own rule: "at the
 * first cross-boundary consumer the record moves to shared/api, by
 * documented decision not improvisation"). The first cross-boundary
 * consumer is the lending module's freeze listener: a dispute opened on a
 * LOAN freezes the loan's ACTIVE edges through {@code Loan.markDisputed()}.
 *
 * <p>The payload carries the subject kind plus the subject's id (exactly
 * one of {@code bookingId}/{@code loanId} is non-null — the V177 pairing
 * constraint is the row-level twin of this invariant) and who opened it —
 * the house's lean event shape (business ids only).
 */
public record DisputeOpenedEvent(UUID disputeId, DisputeSubject subjectType,
                                 UUID bookingId, UUID loanId, UUID openedBy) {
}
