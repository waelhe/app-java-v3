package com.marketplace.payments.spi;

import com.marketplace.shared.api.LedgerAllocationPort;
import com.marketplace.shared.api.PagedResponse;
import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * W11 (the origin-state matrix's G5 closure — ADR-0013 D3): the
 * settlement-eligibility operational read's wire shape — the report the
 * finance operator reads before preparing a payout (ADR-0013 D4: payout is
 * a SEPARATE operational stage, never an automatic effect of this read; the
 * balances are bookkeeping, not stored value).
 *
 * <p>The report composes, per provider: (1) the ledger's current per-
 * currency balances (the books' truth), (2) the per-{@code (origin,
 * currency)} totals — everything credited vs. the portion the eligibility
 * law marks eligible, and (3) the paged per-intent breakdown, each row
 * carrying the derived {@code reason} — an HONEST reason string, never an
 * invented one.
 *
 * <p><b>The reason vocabulary (the law's own words):</b>
 * <ul>
 *   <li>{@link #REASON_SETTLED_NOT_REFUNDED} — collected, not refunded,
 *       and the origin's own eligibility fact is positive: a BOOKING whose
 *       status reads COMPLETED (the same {@code BookingParticipantProvider}
 *       seam the intent's creation gate uses), a LOAN fee (ADR-0013 D3:
 *       "a loan fee settles on collection"), or an AD bill (the intent IS
 *       the charged window's bill — the billing run creates it only when
 *       freezing a window that already ran).</li>
 *   <li>{@link #REASON_REFUNDED} — the intent is REFUNDED in full; the
 *       ledger's REFUND_DEBIT already mirrored the credit back.</li>
 *   <li>{@link #REASON_PARTIALLY_REFUNDED} — ADR-0013 D3's deliberate
 *       ledger no-op: the un-refunded remainder's settlement treatment is
 *       the owner's decision, not invented arithmetic here.</li>
 *   <li>{@link #REASON_DOMAIN_END_UNKNOWN} — THE HONEST MEASURED ABSENCE:
 *       ADR-0013 D3 names the ORDER's FULFILLED state as its eligibility
 *       edge, and NO existing port carries that fact to the payments
 *       module ({@code OrderSellerPort} answers only the seller). The row
 *       is not eligible on the read's own authority — the operator
 *       verifies the order's fulfillment on the order's own surface.</li>
 *   <li>{@link #REASON_DOMAIN_END_NOT_REACHED} — the domain end IS
 *       readable and is not the proper one (a booking still CONFIRMED —
 *       collected but the service has not completed).</li>
 * </ul>
 *
 * <p><b>Measured absences, documented — NOT invented:</b> dispute holds
 * and refund windows (ADR-0013 D3's hold stage) have no facts in the code
 * — an open dispute does not appear on the intent, and no
 * refund-window/deadline column exists anywhere. This read therefore does
 * NOT block on them; surfacing them is an owner decision that would need
 * its own facts first. DELIVERY-origin credits are structurally absent
 * (the ledger's completion listener skips DELIVERY — the dedicated
 * delivery journal is that origin's own settlement gate), so the read
 * covers exactly the four origins the matrix's G5 named.
 */
@NamedInterface("payments-spi")
public record SettlementEligibilityReport(
        UUID providerId,
        List<LedgerAllocationPort.ProviderBalanceFact> balances,
        List<OriginCurrencyTotal> totals,
        PagedResponse<SettlementEligibilityRow> credits
) {

    /** Eligible: collected, not refunded, the origin's own eligibility fact positive. */
    public static final String REASON_SETTLED_NOT_REFUNDED = "SETTLED_NOT_REFUNDED";

    /** Not eligible: refunded in full — the ledger's refund debit already mirrored the credit. */
    public static final String REASON_REFUNDED = "REFUNDED";

    /** Not eligible: the deliberate ledger no-op — the remainder's treatment is an owner decision. */
    public static final String REASON_PARTIALLY_REFUNDED = "PARTIALLY_REFUNDED";

    /** Not eligible on this read's authority: the domain-end fact is not readable through any existing port. */
    public static final String REASON_DOMAIN_END_UNKNOWN = "DOMAIN_END_UNKNOWN";

    /** Not eligible: the domain end is readable and has not reached the proper terminal state. */
    public static final String REASON_DOMAIN_END_NOT_REACHED = "DOMAIN_END_NOT_REACHED";

    /**
     * One {@code (origin, currency)} aggregate over the provider's whole
     * credit set (not only the page): everything credited, the portion the
     * law marks eligible, and how many credits compose it. The gap between
     * {@code creditedCents} and {@code eligibleCents} is the operational
     * worklist — each blocked credit's reason rides its own row.
     */
    public record OriginCurrencyTotal(
            String origin,
            String currency,
            int creditCount,
            long creditedCents,
            long eligibleCents
    ) {
    }

    /**
     * One settled credit with its derived eligibility — the per-intent
     * breakdown row. {@code intentAmountCents} is the payment engine's own
     * amount; {@code creditedCents} is what the ledger actually posted
     * (the BOOKING path credits the booking's frozen price — the two are
     * the same money through different owners' tables, carried side by
     * side honestly); {@code refundedAmountCents} is the intent's own
     * refund history.
     */
    public record SettlementEligibilityRow(
            UUID paymentIntentId,
            UUID providerId,
            String origin,
            UUID subjectId,
            long intentAmountCents,
            long creditedCents,
            long refundedAmountCents,
            String currency,
            boolean eligible,
            String reason
    ) {
    }
}
