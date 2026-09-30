package com.marketplace.payments;

/**
 * R10 (comprehensive-review-ar fix plan §4/R10 — Wave 2): the lifecycle of a
 * webhook inbox row on {@code payment_webhook_events} — the durable inbox the
 * plan models on Spring Modulith's own Event Publication Registry ("the
 * persistent abstraction of them … will grow unbounded" housekeeping pattern;
 * {@code event_publication} + the V28 archive mode is the repository's
 * standing precedent for exactly this structure).
 *
 * <ul>
 *   <li>{@link #RECEIVED} — the row's payload is committed and the dispatch
 *       may or may not have run: the crash window between the recorder's
 *       {@code REQUIRES_NEW} commit and the dispatch's completion is covered
 *       by the recovery sweep ({@code WebhookInboxRecovery}), which re-delivers
 *       every RECEIVED row older than the staleness threshold — the closed
 *       loop with no loss window (the measured defect: a worker stop between
 *       the two committed the dedup row forever while the settlement never
 *       happened — the provider's retries were acknowledged as
 *       already-processed against a tombstone);</li>
 *   <li>{@link #SETTLED} — the dispatch's effects are durably committed. For
 *       the settlement events this mark is written <i>inside the settlement's
 *       own transaction</i> ({@code PaymentIntentSettlementService}), so
 *       "SETTLED" and "the money moved" are one atomic fact; for the
 *       non-settlement events the mark follows the dispatch in the carrier's
 *       transaction (their effects are idempotent by design, so a crash in
 *       the tiny gap self-heals on the next recovery sweep as a no-op);</li>
 *   <li>{@link #FAILED} — a recovery re-delivery that can never apply legally
 *       (the intent moved past the event's transition by another path): the
 *       terminal, inspectable operator signal. The row carries the failure
 *       reason and is <i>never</i> purged by the retention sweep — unlike
 *       SETTLED rows it is the loud residue that demands a human decision
 *       (re-arm by deleting the row, or accept the outcome).</li>
 * </ul>
 *
 * <p>Every state answers the provider's retry through the existing dedup
 * gate ("already processed") — the difference R10 makes is that RECEIVED and
 * FAILED rows are no longer silent: one is re-delivered automatically, the
 * other is visible with its reason.
 */
public enum WebhookProcessingState {
    RECEIVED,
    SETTLED,
    FAILED
}
