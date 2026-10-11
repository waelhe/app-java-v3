package com.marketplace.shared.api;

/**
 * L24 (feature-expansion roadmap §5): the resolve decision's outcome.
 *
 * <p>Moved to {@code shared/api} by ADR-0009 (the parallel contracts
 * ledger's own rule — "at the first cross-boundary consumer the record
 * moves to shared/api, by documented decision not improvisation"): the
 * loan-subject resolution rides {@link DisputeResolvedEvent} to the
 * lending module, so the enum is part of the shared event vocabulary.
 *
 * <p>{@code REFUND_CONSUMER} — the consumer wins: for a BOOKING subject
 * the resolve invokes the existing refund path for the booking's payment
 * and records the movement on the dispute; for a LOAN subject the money
 * rides the lending module's own cancellation chain
 * ({@link LoanCancelledEvent} → the payments module's listener — the ONE
 * refund contract, never reimplemented in disputes);
 * {@code RELEASE_PROVIDER} — the provider wins: the money stays;
 * {@code NO_ACTION} — resolved without a financial movement.
 */
public enum DisputeResolution {
    REFUND_CONSUMER,
    RELEASE_PROVIDER,
    NO_ACTION
}
