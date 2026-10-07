package com.marketplace.catalog;

import org.springframework.context.event.EventListener;
import org.springframework.modulith.moments.HourHasPassed;
import org.springframework.stereotype.Component;

/**
 * L33 (realestate systems plan §5) + C.6 (the Moments migration): the listing
 * expiry job — a listener on Modulith Moments' {@link HourHasPassed}, the
 * passage-of-time event that replaces the former manual {@code @Scheduled}
 * cron (the compliance plan's own target: «الزمن بالأحداث لا بالمجدولات
 * اليدوية» — time as events, deterministically testable through the
 * {@code TimeMachine}). Every ACTIVE listing whose {@code expires_at} has
 * passed moves to PAUSED with the DURABLE {@code EXPIRED} marker (the same
 * state machine transition as the provider's pause — D-R7: zero machine
 * changes), idempotently (the status itself is the guard: a re-run finds
 * nothing new).
 *
 * <p><b>The Moments cadence (the C.6 decision, stated honestly):</b> Moments'
 * minimum granularity is the hour ({@code spring.modulith.moments.granularity}
 * = {@code hours|days} — the official property set), so the former
 * every-30-minutes cron becomes the hourly tick: a listing's pause now lands
 * within the hour after expiry rather than within thirty minutes. The
 * executor's own boundary law is unchanged — "expires_at exactly now" stays
 * ACTIVE (the predicate is strictly {@code before now}; a listing expiring
 * this instant still serves its last request; the next tick catches it).
 *
 * <p>CodeRabbit PR #299 round 1 (two findings, one root): the transaction
 * boundary and the page-advance. The orchestrator itself is NOT
 * transactional — {@link ListingExpiryBatchExecutor} runs each batch in its
 * own {@code REQUIRES_NEW} transaction and re-queries page ZERO every time
 * (paused rows leave the {@code status = ACTIVE} match set, so the remaining
 * expired rows always start at page zero — a batch can never be skipped).
 * Each batch's commit carries its own cache eviction through the
 * AFTER_COMMIT relay.
 */
@Component
public class ListingExpiryJob {

    /** Page size — bounded work per transaction, the house batch discipline. */
    static final int BATCH_SIZE = 500;

    /** Safety valve: even a pathological backlog ends one run (progress is durable). */
    static final int MAX_BATCHES_PER_RUN = 1000;

    private final ListingExpiryBatchExecutor batchExecutor;

    public ListingExpiryJob(ListingExpiryBatchExecutor batchExecutor) {
        this.batchExecutor = batchExecutor;
    }

    /**
     * The hourly tick — Moments publishes the event at each hour boundary on
     * its own scheduler thread, and this plain {@code @EventListener} runs
     * synchronously on it: the exact serialization the jobs already had on
     * the default single-threaded {@code TaskScheduler}, preserved with zero
     * new concurrency.
     *
     * <p><b>Why a plain listener and NOT the house {@code @ApplicationModuleListener}
     * (the availability/booking precedent):</b> Moments publishes from its
     * {@code @Scheduled} thread with NO transaction, and the official
     * Framework rule for {@code @TransactionalEventListener} is explicit —
     * "if no transaction is running, the listener is not invoked at all"
     * (unless {@code fallbackExecution = true}, which
     * {@code @ApplicationModuleListener} does not set). A registry-tracked
     * listener here would defer every tick's delivery to the boot-time
     * republish or the staleness-to-FAILED recovery chain — the job would
     * NOT run when the hour passes. The temporal jobs' own idempotent
     * cadence IS their retry (the next tick resumes from the true backlog),
     * so they take the guaranteed at-tick delivery instead of the durable
     * deferred one.
     */
    @EventListener
    void on(HourHasPassed event) {
        pauseExpiredListings();
    }

    /**
     * One run over the true backlog. Each batch commits independently, so a
     * run interrupted mid-way leaves the completed batches done and the next
     * tick resumes from the true backlog.
     */
    void pauseExpiredListings() {
        int batches = 0;
        int paused;
        do {
            paused = batchExecutor.pauseOneBatch();
            batches++;
        } while (paused == BATCH_SIZE && batches < MAX_BATCHES_PER_RUN);
    }
}
