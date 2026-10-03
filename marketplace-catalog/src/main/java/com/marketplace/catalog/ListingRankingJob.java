package com.marketplace.catalog;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * W3 (yelp-level plan §5 — G18): the daily ranking job — the composite
 * score's standing refresher («عمودًا محسوبًا بجدول يومي قائم» — the plan's
 * own wording; the ListingExpiryJob / EventPublicationCleanup pattern: a
 * framework-scheduled component, cron + UTC zone, no Quartz anywhere —
 * V31 measured that out of every pom).
 *
 * <p>Cadence: daily at 03:30 UTC — an off-peak hour deliberately clear of
 * the expiry job's every-30-minutes ticks. The run is keyset-paged (the
 * executor's own law), each batch commits independently, and an
 * interrupted run's completed batches stay done — the next night's run
 * resumes from the true roster (the score is idempotent: recomputing an
 * unchanged listing writes nothing).
 */
@Component
public class ListingRankingJob {

    /** Page size — bounded work per transaction, the house batch discipline. */
    static final int BATCH_SIZE = 500;

    /** Safety valve: even a pathological roster ends one run (progress is durable). */
    static final int MAX_BATCHES_PER_RUN = 1000;

    private final ListingRankingBatchExecutor batchExecutor;

    public ListingRankingJob(ListingRankingBatchExecutor batchExecutor) {
        this.batchExecutor = batchExecutor;
    }

    /**
     * Runs daily (the plan's cadence). The keyset cursor advances by each
     * batch's last id; a null cursor (an empty batch) drains the run.
     */
    @Scheduled(cron = "0 30 3 * * ?", zone = "UTC")
    public void refreshRankingScores() {
        UUID cursor = null;
        int batches = 0;
        do {
            cursor = batchExecutor.rankOneBatch(cursor, BATCH_SIZE);
            batches++;
        } while (cursor != null && batches < MAX_BATCHES_PER_RUN);
    }
}
