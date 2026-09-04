package com.marketplace.shared.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically re-submits failed event publications through the official
 * Modulith API, so a failed listener is recovered <i>at runtime</i> — without
 * waiting for the next application restart.
 *
 * <p><b>Why this component exists (the gap it closes).</b> The runtime
 * lifecycle of a publication in Spring Modulith 2.1.1 is
 * PUBLISHED&nbsp;&rarr;&nbsp;PROCESSING&nbsp;&rarr;&nbsp;COMPLETED/FAILED, and
 * a listener that throws marks its publication FAILED immediately (verified in
 * the shipped bytecode: {@code CompletionRegisteringAdvisor$Completion-
 * RegisteringMethodInterceptor} invokes {@code handleFailure(...)} on any
 * listener exception). The official Staleness Monitor
 * ({@code spring.modulith.events.staleness.*}, activated in
 * {@code application-prod.yml}) converts stuck non-terminal states to FAILED.
 * But re-delivery of FAILED publications happens <b>only at application
 * restart</b> via {@code spring.modulith.events.republish-outstanding-events-
 * on-restart} — and a Railway deployment restarts only when new code is
 * deployed. A listener that failed at runtime (transient fault, or an external
 * integration that comes back later, e.g. the SMTP provider gate) would
 * otherwise keep its publication FAILED for days: the event — a booking or
 * payment notification — stays undelivered until someone redeploys. That is
 * manual intervention by another name.
 *
 * <p><b>Official mechanism.</b> Spring Modulith 2.1.1 Reference, "Failed
 * publications and resubmission": "{@code FailedEventPublications} (since 2.0)
 * – Use the bean of this type to resubmit only failed publications:
 * {@code resubmit(ResubmissionOptions)}." The bean is the application context's
 * {@code PersistentApplicationEventMulticaster} (implements
 * {@code FailedEventPublications}); resubmission sets the status to
 * RESUBMITTED, increments the completion-attempt count, and re-dispatches the
 * event to its listeners.
 *
 * <p><b>Retry policy (bounded, self-healing).</b> The filter implements the
 * time-based policy the reference suggests ("resubmit only if failed longer
 * than X"): a FAILED publication is retried as soon as it has never been
 * resubmitted (first retry within one sweep), then at most once per
 * {@link #RETRY_BACKOFF}. One retry per publication per day is negligible load
 * and cannot turn into a retry storm, yet it converges automatically: when the
 * root cause of the failure is resolved (e.g. a mail provider is configured),
 * every queued publication is delivered on its next due retry with zero manual
 * steps. Listeners in this system are idempotent by design (documented in
 * SYSTEM.md §5: slot generation skips existing rows, cancellation filters
 * PENDING), so re-delivery is safe. Publications that keep failing remain
 * FAILED and unresolved — surfaced truthfully by
 * {@code ModulithEventBusHealthIndicator} (aggregate health reports DOWN while
 * events are undelivered) instead of being silently dropped.
 *
 * <p>Shape mirrors {@link EventPublicationCleanup} (same package,
 * {@code @EnableScheduling} via {@code CacheConfig}): the purge of completed
 * publications runs daily at 03:00 UTC, this recovery sweep runs continuously
 * every 15 minutes.
 */
@Component
public class EventPublicationResubmission {

    private static final Logger log = LoggerFactory.getLogger(EventPublicationResubmission.class);

    /**
     * Minimum spacing between two delivery attempts of the same failed
     * publication. One retry per publication per day: bounded load, convergent
     * recovery, no retry storm. Public test seam:
     * {@code EventPublicationResubmissionIntegrationTest} (different package)
     * drives the sweep with a controlled clock.
     */
    public static final Duration RETRY_BACKOFF = Duration.ofHours(24);

    /**
     * Recovery cadence: how often failed publications are checked for a due
     * retry. A freshly failed publication gets its first retry within one
     * sweep; transient faults heal within minutes, not at the next deploy.
     */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(15);

    private final FailedEventPublications failedPublications;

    public EventPublicationResubmission(FailedEventPublications failedPublications) {
        this.failedPublications = failedPublications;
    }

    /**
     * Re-submits FAILED publications whose next retry is due. Runs every 15
     * minutes (fixed delay — sweeps never overlap). The 5-minute initial
     * delay keeps the first sweep clear of application warm-up: at boot the
     * framework's own {@code republish-outstanding-events-on-restart} already
     * re-delivers outstanding publications, and firing into a shutting-down
     * context (CI evidence, PR #210 round 2: "Unexpected error occurred in
     * scheduled task" — connection EOF against a container mid-teardown) is
     * pure noise. Official defaults are kept for batch size (100) and max
     * in-flight; the only policy applied is the {@link #RETRY_BACKOFF} filter.
     */
    @Scheduled(fixedDelay = 15, initialDelay = 5, timeUnit = TimeUnit.MINUTES)
    void resubmitFailedPublications() {
        resubmitDue(Instant.now());
    }

    /**
     * The sweep, parameterized on the clock — the test seam
     * {@code EventPublicationResubmissionIntegrationTest} (different package)
     * drives it directly. Resubmits every FAILED publication that was never
     * resubmitted (first retry) or whose last resubmission precedes
     * {@code now - RETRY_BACKOFF}.
     *
     * <p>Legacy pre-lifecycle rows ({@code status IS NULL AND completion_date
     * IS NULL}) are included by the framework's own failed-criteria query and
     * receive the same policy.
     *
     * @param now the operational "now" the backoff window is measured against
     */
    public void resubmitDue(Instant now) {
        Instant cutoff = now.minus(RETRY_BACKOFF);
        failedPublications.resubmit(ResubmissionOptions.defaults()
                .withFilter(publication -> dueForRetry(publication, cutoff)));
    }

    /**
     * A publication is due when it has never been resubmitted (its last
     * resubmission date is null) or its last resubmission lies before the
     * backoff cutoff. Everything else — recently resubmitted, already retried
     * within the window — is left alone so the sweep cannot hammer a failing
     * listener.
     */
    private static boolean dueForRetry(EventPublication publication, Instant cutoff) {
        Instant lastResubmission = publication.getLastResubmissionDate();
        return lastResubmission == null || lastResubmission.isBefore(cutoff);
    }
}
