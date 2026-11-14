package com.marketplace.ledger;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * F-832-1 + CodeRabbit round 2 (the fee mirror's deploy-overlap closure):
 * the POST-STOP pass of the delivery-fee-reversal reconciliation — the fee
 * twin of {@link CommissionReversalReconciliation}, answering the round-2
 * review's Major with the house's own accepted pattern.
 *
 * <p><b>The window this sweep closes.</b> Railway keeps the PREVIOUS
 * deployment serving while the new one boots and runs Flyway: during that
 * minutes-bounded window the OLD deployment's refund listener (pre-F-832-1)
 * still writes the refund leg WITHOUT the fee mirror (a REFUND_DEBIT with
 * no DELIVERY_FEE_REVERSAL_CREDIT) and its event publications COMPLETE —
 * the pre-fix code does not fail, so the registry never replays those
 * deliveries. The fee pair carries no one-time migration (the feature ships
 * with this branch), so this component is the fee mirror's whole convergence
 * story: every refund the old version processed after the new version's
 * boots is converged here, at minus the fee — the same exposure shape the
 * commission sweep exists for (the A2 round-8 closure), mirrored.</p>
 *
 * <p><b>The mechanism.</b> The same idempotent one-statement convergence
 * {@code LedgerEntryRepository#reconcileMissingDeliveryFeeReversals} carries:
 * every REFUNDED intent holding the settlement's DELIVERY_FEE_DEBIT and the
 * refund's REFUND_DEBIT but not the pair's mirror gains the missing reversal
 * row — the POSTED fee row's own amount/currency/provider, the deterministic
 * {@code delivery-fee-reversal-<intentId>} source id — and the provider's
 * balance moves by the sum, once per balance key. Zero-fee refunds (the
 * PICKUP order's documented no-op) wrote no fee row and are correctly left
 * alone. A converged world answers zero and every later sweep is a no-op.</p>
 *
 * <p>Shape mirrors {@code CommissionReversalReconciliation} verbatim: fixed
 * delay, initial delay clear of boot, a public test seam {@link
 * #reconcileNow()} for the guards, and an INFO line only when the world
 * actually moved — the sweep is silent when idle.</p>
 */
@Component
public class DeliveryFeeReversalReconciliation {

    private static final Logger log = LoggerFactory.getLogger(DeliveryFeeReversalReconciliation.class);

    private final LedgerEntryRepository entryRepository;

    public DeliveryFeeReversalReconciliation(LedgerEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    /**
     * The convergence sweep — hourly, idempotent, silent once converged.
     * The 10-minute initial delay keeps the first sweep clear of boot (the
     * deploy-overlap stragglers the runtime path itself cannot see are the
     * sweep's own catch). {@code @Transactional} sits here too (the
     * scheduler enters through the proxy; the inner self-call then simply
     * joins this transaction — a bare self-invocation would have bypassed
     * the proxy and left the modifying query without one).
     */
    @Scheduled(fixedDelay = 60, initialDelay = 10, timeUnit = TimeUnit.MINUTES)
    @Transactional
    void reconcileMissingDeliveryFeeReversals() {
        reconcileNow();
    }

    /**
     * The sweep, callable directly — the test seam. Returns the number of
     * missing fee-reversal entries created this run: zero once the world has
     * converged, the sweep's own idle signal.
     */
    @Transactional
    public int reconcileNow() {
        int converged = entryRepository.reconcileMissingDeliveryFeeReversals();
        if (converged > 0) {
            log.info("Delivery-fee-reversal reconciliation converged {} missing mirror row(s) — the "
                            + "deploy-overlap stragglers the pre-mirror refund listener left behind",
                    converged);
        }
        return converged;
    }
}
