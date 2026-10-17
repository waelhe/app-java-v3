package com.marketplace.delivery;

import com.marketplace.shared.api.PaymentIntentLookupPort;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The V229 settlement reconciliation (the architecture §4.2: «Reconciliation
 * of quoted charge vs. collected amount vs. partner invoice/courier
 * compensation/platform fee; correction is an audited financial
 * operation»): the OPERATIONAL read over a time window — per currency,
 * the quoted charges, the collected amounts, the courier payables, and
 * the platform margins, with the two leg families kept honestly apart:
 *
 * <ul>
 *   <li><b>Quote-borne legs</b> (the #604 direct/manual path): the
 *       collection is the leg's OWN payment intent — its state resolves
 *       through {@link PaymentIntentLookupPort} (the existing payments
 *       seam; no payment internals leak).</li>
 *   <li><b>Order-borne legs</b> (the V224 checkout path): the fee rode
 *       the ORDER's one intent — the leg itself collected nothing and its
 *       amount is the frozen shipping share; these legs report under
 *       {@code orderBorneQuoted} (their financial reconciliation is the
 *       ORDER journey's own, listed separately — never double-counted
 *       here).</li>
 * </ul>
 *
 * <p>The financial BOOKS remain the ledger's balanced delivery journal
 * (the V215 postings); THIS is the operations workbench's read that
 * surfaces mismatches for the finance operator to act on through the
 * audited commands.
 */
@Service
@Transactional(readOnly = true)
public class DeliveryReconciliationService {

    private final DeliveryRequestRepository requests;
    private final PaymentIntentLookupPort paymentIntents;

    public DeliveryReconciliationService(DeliveryRequestRepository requests,
                                         PaymentIntentLookupPort paymentIntents) {
        this.requests = requests;
        this.paymentIntents = paymentIntents;
    }

    /**
     * The window's reconciliation: per currency — the quoted charges (all
     * legs), the COLLECTED amounts (quote-borne legs whose intent
     * COMPLETED), the courier payables, and the order-borne quoted totals
     * kept apart.
     */
    @Observed(name = "delivery.reconciliation.window")
    public ReconciliationReport reconcile(Instant from, Instant to) {
        List<DeliveryRequest> window = requests.findByCreatedAtBetween(from, to);
        Accumulator quoted = new Accumulator();
        Accumulator collected = new Accumulator();
        Accumulator payables = new Accumulator();
        Accumulator orderBorne = new Accumulator();
        for (DeliveryRequest leg : window) {
            // every leg's amount is a QUOTED charge of the window — the
            // order-borne share is listed AGAIN in its own row for
            // visibility (a subset of quoted, never an addition to it)
            quoted.add(leg.getCurrency(), leg.getAmountMinor());
            if (leg.getPaymentIntentId() != null) {
                boolean completed = paymentIntents.findById(leg.getPaymentIntentId())
                        .map(intent -> "SUCCEEDED".equals(intent.status())
                                || "PARTIALLY_REFUNDED".equals(intent.status()))
                        .orElse(false);
                if (completed) {
                    collected.add(leg.getCurrency(), leg.getAmountMinor());
                }
            } else {
                orderBorne.add(leg.getCurrency(), leg.getAmountMinor());
            }
            payables.add(leg.getCurrency(), leg.getCourierCompensationMinor());
        }
        return new ReconciliationReport(from, to, window.size(),
                quoted.rows(), collected.rows(), payables.rows(), orderBorne.rows());
    }

    /** One currency's reconciliation row — the wire shape. */
    public record CurrencyRow(String currency, long amountMinor) {
    }

    /** The window's report. */
    public record ReconciliationReport(Instant from, Instant to, int legs,
                                       List<CurrencyRow> quoted,
                                       List<CurrencyRow> collected,
                                       List<CurrencyRow> courierPayables,
                                       List<CurrencyRow> orderBorneQuoted) {
    }

    /** The per-currency accumulator (LinkedHashMap — deterministic row order). */
    private static final class Accumulator {
        private final Map<String, long[]> totals = new LinkedHashMap<>();

        void add(String currency, long amountMinor) {
            totals.computeIfAbsent(currency, c -> new long[1])[0] += amountMinor;
        }

        List<CurrencyRow> rows() {
            List<CurrencyRow> rows = new ArrayList<>(totals.size());
            totals.forEach((currency, total) -> rows.add(new CurrencyRow(currency, total[0])));
            return rows;
        }
    }
}
