package com.marketplace.payments;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;

/**
 * R10 (comprehensive-review-ar fix plan §4/R10 — Wave 2): the webhook inbox's
 * recovery sweep — the closing half of the durable inbox. The recorder
 * commits every inbox row (RECEIVED) in its own transaction BEFORE the
 * dispatch runs; the measured defect was the crash window between those two
 * steps: a worker stop (deploy, OOM, node loss) left the dedup row committed
 * while the settlement never happened — the provider's retries were answered
 * already-processed against a tombstone, and the money transition was lost
 * permanently. This sweep re-delivers every RECEIVED row older than the
 * staleness threshold through the SAME dispatch contract the original
 * delivery used, so the inbox is exactly the structure Spring Modulith's
 * Event Publication Registry gives domain events ("the persistent
 * abstraction of them", completed rows archived, incomplete rows
 * re-delivered) — applied to the provider's money-critical notifications.
 *
 * <p><b>Why a component and not a framework flag.</b> The provider webhook is
 * not a Modulith event publication — it arrives over HTTP from outside the
 * application, so the registry cannot see it. The durable inbox + this sweep
 * give it the same at-least-once delivery discipline the registry gives
 * domain events, using the repository's own standing machinery: Spring
 * {@code @Scheduled} exclusively (SYSTEM.md §7 — the scheduling model since
 * the Quartz store removal V31, guarded by
 * {@code DeadQuartzStoreRemovalIntegrationTest}), the same single-instance
 * design constraint every other {@code @Scheduled} task documents (the
 * migration runbook's measured statement: no ShedLock, scheduling is
 * Spring-pure single-replica by design), and the
 * {@code ExpiredAuthorizationsCleanup}/{@code EventPublicationResubmission}
 * component shapes (a public test seam parameterized on the clock, constants
 * as policy).
 *
 * <p><b>Re-delivery failure families (bounded, truthful).</b> A re-delivery
 * that fails with a state-machine conflict ({@link ConflictException}) or a
 * missing target ({@link ResourceNotFoundException}) can never apply
 * legally — the intent moved past the event's transition by another path —
 * so the row is marked {@link WebhookProcessingState#FAILED} with its
 * inspectable reason and STOPS being retried (the S12 lesson: a permanently
 * failing re-delivery resubmitted every sweep is the daily-forever pathology;
 * FAILED is the loud operator residue instead). Any other exception is
 * treated as transient infrastructure: the row stays RECEIVED and the next
 * sweep retries it — bounded by one attempt per sweep, no retry storm.
 *
 * <p><b>Retention (the {@code ExpiredAuthorizationsCleanup} pattern).</b>
 * SETTLED rows are inert history: the dedup answer they carry only matters
 * while the provider may still retry the event id (Stripe retries within 3
 * days of the first attempt — official docs), so a 7-day retention —
 * symmetric with {@code EventPublicationCleanup}'s completed-publication
 * purge — deletes them with a hard {@code DELETE} through JDBC (never the
 * soft-delete derived {@code deleteBy}: purged means gone, and the audit
 * trail of the row's live lifetime stays in Envers). FAILED rows are NEVER
 * purged: they are the operator signal this component exists to surface.
 */
@Component
public class WebhookInboxRecovery {

    private static final Logger log = LoggerFactory.getLogger(WebhookInboxRecovery.class);

    /**
     * The staleness threshold: a RECEIVED row younger than this may still be
     * mid-dispatch in its original delivery's thread (record's REQUIRES_NEW
     * committed; the dispatch has not finished), so the sweep leaves it
     * alone. Webhook dispatches complete in seconds; two minutes exceeds any
     * legitimate in-flight dispatch with an order of magnitude to spare.
     * Public test seam policy constant, asserted by
     * {@code WebhookInboxRecoveryIntegrationTest} (different package).
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(2);

    /**
     * Retention of SETTLED inbox rows before the purge sweep may delete
     * them — symmetric with {@code EventPublicationCleanup}'s 7 days, and
     * beyond any provider's retry horizon (Stripe retries within 3 days).
     */
    public static final Duration SETTLED_RETENTION = Duration.ofDays(7);

    /**
     * The recovery sweep's hard delete of inert SETTLED rows — raw JDBC for
     * the same reason {@code ExpiredAuthorizationsCleanup} uses it: a hard
     * DELETE, not the entity layer's soft-delete translation.
     */
    // @formatter:off
    static final String DELETE_SETTLED_BEFORE = """
            DELETE FROM payment_webhook_events
             WHERE processing_state = 'SETTLED'
               AND is_deleted = FALSE
               AND created_at < :cutoff
            """;
    // @formatter:on

    private final PaymentWebhookEventRepository repository;
    private final PaymentsService paymentsService;
    private final WebhookEventRecorder webhookEventRecorder;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public WebhookInboxRecovery(PaymentWebhookEventRepository repository,
                                PaymentsService paymentsService,
                                WebhookEventRecorder webhookEventRecorder,
                                DataSource dataSource) {
        this.repository = repository;
        this.paymentsService = paymentsService;
        this.webhookEventRecorder = webhookEventRecorder;
        // NamedParameterJdbcTemplate over the application DataSource — the
        // ExpiredAuthorizationsCleanup construction: the same JdbcTemplate
        // infrastructure, shared pool and transaction semantics, no second
        // pool. (The recorder's own transactional methods manage the inbox
        // state transitions; only the purge bypasses the entity layer.)
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * The recovery sweep — every minute (fixed delay: sweeps never overlap),
     * the initial delay keeping the first sweep clear of application
     * warm-up. Freshly recorded rows are filtered out by the staleness
     * threshold, so the steady-state sweep reads an empty partial index
     * (V71) and costs one indexed lookup.
     */
    @Scheduled(fixedDelay = 1, initialDelay = 2, timeUnit = TimeUnit.MINUTES)
    void recoverStaleInboxRows() {
        recoverDue(Instant.now());
    }

    /**
     * The sweep, parameterized on the clock — the test seam
     * ({@code WebhookInboxRecoveryIntegrationTest}, different package) drives
     * it directly. Re-delivers every RECEIVED inbox row whose record
     * committed before {@code now - STALE_AFTER} through the same dispatch
     * contract the original delivery used (settlement events settle + mark
     * atomically; non-settlement effects are idempotent by design).
     *
     * @param now the operational "now" the staleness window is measured against
     */
    public void recoverDue(Instant now) {
        List<PaymentWebhookEvent> stale = repository
                .findByProcessingStateAndCreatedAtBefore(WebhookProcessingState.RECEIVED,
                        now.minus(STALE_AFTER));
        for (PaymentWebhookEvent row : stale) {
            redeliver(row);
        }
        if (!stale.isEmpty()) {
            log.info("Webhook inbox recovery swept {} stale RECEIVED row(s)", stale.size());
        } else {
            log.debug("Webhook inbox recovery sweep — nothing stale");
        }
    }

    /**
     * One row's re-delivery. The provider was already answered for this event
     * id (its retry met the dedup gate), so the compensating delete does NOT
     * apply here — deleting would lose the event permanently. Failure
     * families: see the class Javadoc (permanent → FAILED with reason;
     * transient → stays RECEIVED for the next sweep).
     */
    private void redeliver(PaymentWebhookEvent row) {
        try {
            // The public proxy-safe entry point (the N2 proxying lesson — the
            // package-private dispatch form is for self-invocation only).
            paymentsService.redeliverWebhookEvent(row.getProvider(), row.getEventId(),
                    row.getEventType(), row.getPaymentIntentId(), row.getExternalId(),
                    row.getRefundAmountCents());
            // Idempotent backstop — the settlement types were already marked
            // inside the settlement's own transaction.
            webhookEventRecorder.markSettled(row.getProvider(), row.getEventId());
        } catch (ConflictException | ResourceNotFoundException ex) {
            webhookEventRecorder.markFailed(row.getProvider(), row.getEventId(), ex.getMessage());
        } catch (RuntimeException ex) {
            log.error("Webhook inbox re-delivery for event {} ({}) failed transiently — the row stays"
                    + " RECEIVED and the next sweep retries it. Failure:",
                    row.getEventId(), row.getProvider(), ex);
        }
    }

    /**
     * Purges SETTLED inbox rows older than the retention window. Runs daily
     * at 05:00 UTC — one hour after the 04:00 authorization sweep, two after
     * the 03:00 event-publications purge, so the three housekeeping sweeps
     * never contend on the scheduler thread.
     */
    @Scheduled(cron = "0 0 5 * * ?", zone = "UTC")
    void purgeSettledRows() {
        int purged = purgeSettledBefore(Instant.now().minus(SETTLED_RETENTION));
        if (purged > 0) {
            log.info("Purged {} SETTLED webhook inbox rows (older than {})", purged, SETTLED_RETENTION);
        } else {
            log.debug("No SETTLED webhook inbox rows older than {} to purge", SETTLED_RETENTION);
        }
    }

    /**
     * The purge, parameterized on the cutoff — the test seam. FAILED rows are
     * never purged (the operator signal); soft-deleted rows are already gone
     * to every query and are collected with the same sweep.
     *
     * @return number of rows deleted
     */
    public int purgeSettledBefore(Instant cutoff) {
        return jdbcTemplate.update(DELETE_SETTLED_BEFORE,
                java.util.Map.of("cutoff", java.sql.Timestamp.from(cutoff)));
    }
}
