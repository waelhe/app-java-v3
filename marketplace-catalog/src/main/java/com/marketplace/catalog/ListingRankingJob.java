package com.marketplace.catalog;

import org.springframework.context.event.EventListener;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * W3 (yelp-level plan §5 — G18) + C.6 (the Moments migration): the daily
 * ranking job — the composite score's standing refresher («عمودًا محسوبًا
 * بجدول يومي قائم» — the plan's own wording), now a listener on Modulith
 * Moments' {@link DayHasPassed} instead of the former manual
 * {@code @Scheduled} cron (the compliance plan's own target: «الزمن بالأحداث
 * لا بالمجدولات اليدوية» — time as events, deterministically testable
 * through the {@code TimeMachine}). No Quartz anywhere — V31 measured that
 * out of every pom, and Moments itself is a {@code @Scheduled}-based
 * framework component.
 *
 * <p><b>The Moments cadence (the C.6 decision, stated honestly):</b> the run
 * follows the day boundary — Moments publishes {@link DayHasPassed} at each
 * midnight UTC (the default {@code spring.modulith.moments.zone-id}), where
 * the former cron fired at 03:30. The off-peak-hour choice was a scheduler's
 * luxury; the event IS the day's edge. The run is keyset-paged (the
 * executor's own law), each batch commits independently, and an interrupted
 * run's completed batches stay done — the next night's tick resumes from the
 * true roster (the score is idempotent: recomputing an unchanged listing
 * writes nothing).
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
     * The daily tick — a plain {@code @EventListener} running synchronously
     * on Moments' scheduler thread: the exact serialization the jobs already
     * had on the default single-threaded {@code TaskScheduler}, preserved
     * with zero new concurrency. Deliberately NOT the house
     * {@code @ApplicationModuleListener} (the availability/booking
     * precedent): Moments publishes with no transaction and the official
     * Framework rule says a {@code @TransactionalEventListener} without one
     * "is not invoked at all" — a registry-tracked listener would defer this
     * tick's delivery to boot-time republish or staleness recovery. The
     * ranking's own idempotent cadence IS its retry (the next tick recomputes
     * an unchanged roster to nothing).
     */
    @EventListener
    void on(DayHasPassed event) {
        refreshRankingScores();
    }

    /**
     * Runs daily (the plan's cadence). The keyset cursor advances by each
     * batch's last id; a null cursor (an empty batch) drains the run.
     */
    void refreshRankingScores() {
        UUID cursor = null;
        int batches = 0;
        do {
            cursor = batchExecutor.rankOneBatch(cursor, BATCH_SIZE);
            batches++;
        } while (cursor != null && batches < MAX_BATCHES_PER_RUN);
    }
}
