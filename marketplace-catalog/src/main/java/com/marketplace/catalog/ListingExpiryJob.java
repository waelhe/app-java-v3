package com.marketplace.catalog;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * L33 (realestate systems plan §5): the listing expiry job — the
 * {@code EventPublicationCleanup} pattern (a framework-scheduled component,
 * cron + UTC zone). Every ACTIVE listing whose {@code expires_at} has
 * passed moves to PAUSED with the DURABLE {@code EXPIRED} marker (the
 * same state machine transition as the provider's pause — D-R7: zero
 * machine changes), idempotently (the status itself is the guard: a
 * re-run finds nothing new).
 *
 * <p>CodeRabbit PR #299 round 1 (two findings, one root): the transaction
 * boundary and the page-advance. The orchestrator itself is NOT
 * transactional — {@link ListingExpiryBatchExecutor} runs each batch in
 * its own {@code REQUIRES_NEW} transaction and re-queries page ZERO
 * every time (paused rows leave the {@code status = ACTIVE} match set, so
 * the remaining expired rows always start at page zero — a batch can
 * never be skipped). Each batch's commit carries its own cache eviction
 * through the AFTER_COMMIT relay.
 *
 * <p>The boundary case "expires_at exactly now" stays ACTIVE (the predicate
 * is strictly {@code before now} — a listing expiring this instant still
 * serves its last request; the next tick catches it).
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
     * Runs every 30 minutes (the plan's cadence — the classifieds' expiry
     * precision is half an hour, not a second). Each batch commits
     * independently, so a run interrupted mid-way leaves the completed
     * batches done and the next tick resumes from the true backlog.
     */
    @Scheduled(cron = "0 */30 * * * ?", zone = "UTC")
    public void pauseExpiredListings() {
        int batches = 0;
        int paused;
        do {
            paused = batchExecutor.pauseOneBatch();
            batches++;
        } while (paused == BATCH_SIZE && batches < MAX_BATCHES_PER_RUN);
    }
}
