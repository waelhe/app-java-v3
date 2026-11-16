package com.marketplace.ledger;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A2 + CodeRabbit round 8 (the deploy-overlap closure): the POST-STOP pass
 * of the commission-reversal reconciliation — the reviewer's requested
 * later convergence that runs AFTER the previous deployment is gone.
 *
 * <p><b>The window this sweep closes.</b> Railway keeps the PREVIOUS
 * deployment serving while the new one boots and runs Flyway: during that
 * minutes-bounded window the OLD deployment's refund listener still writes
 * the PRE-A2 shape (a REFUND_DEBIT with no COMMISSION_REVERSAL_CREDIT) and
 * its event publications COMPLETE — the pre-fix code does not fail, so the
 * registry never replays those deliveries. V298's one-time snapshot ran
 * before the old version stopped and cannot see them, and Flyway never
 * re-runs an applied migration. Those refunds stay unmirrored — at minus
 * the commission — until something converges them: this component.</p>
 *
 * <p><b>The mechanism.</b> The same idempotent one-statement convergence
 * {@code LedgerEntryRepository#reconcileMissingCommissionReversals} carries
 * (the migration's own twin): every intent holding the settlement's
 * COMMISSION_DEBIT and the refund's REFUND_DEBIT but not the pair's mirror
 * gains the missing reversal row — the posted commission row's own
 * amount/currency/provider, the deterministic
 * {@code commission-reversal-<intentId>} source id — and the provider's
 * balance moves by the sum, once per balance key. Zero-commission refunds
 * wrote no commission row and are correctly left alone (the round-3
 * zero-separation). A converged world answers zero and every later sweep
 * is a no-op (the V57 re-runnable letter, on a schedule).</p>
 *
 * <p>Shape mirrors {@code EventPublicationResubmission} (the house sweep
 * pattern): fixed delay, initial delay clear of boot (the deploy-time pass
 * V298 already converged everything the snapshot COULD see), a public
 * test seam {@link #reconcileNow()} for the guards, and an INFO line only
 * when the world actually moved — the sweep is silent when idle.</p>
 */
@Component
public class CommissionReversalReconciliation {

    private static final Logger log = LoggerFactory.getLogger(CommissionReversalReconciliation.class);

    private final LedgerEntryRepository entryRepository;

    public CommissionReversalReconciliation(LedgerEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    /**
     * The convergence sweep — hourly, idempotent, silent once converged.
     * The 10-minute initial delay keeps the first sweep clear of boot:
     * V298's deploy-time pass already converged everything its snapshot
     * could see; this pass exists for the deploy-overlap stragglers the
     * snapshot could not. {@code @Transactional} sits here too (the
     * scheduler enters through the proxy; the inner self-call then simply
     * joins this transaction — a bare self-invocation would have bypassed
     * the proxy and left the modifying query without one).
     */
    @Scheduled(fixedDelay = 60, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Transactional
    void reconcileMissingCommissionReversals() {
        reconcileNow();
    }

    /**
     * The sweep, callable directly — the test seam (the
     * {@code LedgerCommissionReversalBackfillMigrationIntegrationTest}
     * guard drives it on the real schema). Returns the number of missing
     * reversal entries created this run: zero once the world has converged,
     * the sweep's own idle signal.
     */
    @Transactional
    public int reconcileNow() {
        int converged = entryRepository.reconcileMissingCommissionReversals();
        if (converged > 0) {
            log.info("Commission-reversal reconciliation converged {} missing mirror row(s) — the "
                            + "deploy-overlap stragglers the migration's snapshot could not see",
                    converged);
        }
        return converged;
    }
}
