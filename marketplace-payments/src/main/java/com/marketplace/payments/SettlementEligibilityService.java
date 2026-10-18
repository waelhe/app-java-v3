package com.marketplace.payments;

import com.marketplace.payments.spi.SettlementEligibilityReport;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.LedgerReadPort;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * W11 — the settlement-eligibility OPERATIONAL READ (the origin-state
 * matrix's G5 closure, built on ADR-0013 D3's own law): for one provider,
 * which settled credits are eligible for settlement and — just as
 * importantly — WHICH HONEST REASON blocks each one that is not. This is a
 * READ above the existing ledger entries and payment intents: no state
 * machine, no money movement, no new accounting (ADR-0013's consequences
 * record verbatim: "the settlement eligibility and holds are
 * read-model/operational surfaces over the existing entries, not a second
 * accounting engine"). Payout stays a separate operational stage (D4) —
 * nothing here moves money or records that a payout happened.
 *
 * <p><b>The eligibility law, per origin (ADR-0013 D3 measured from the
 * code):</b>
 * <ul>
 *   <li><b>BOOKING</b> — "a booking's settlement eligibility is its
 *       COMPLETED state", read through the SAME
 *       {@code BookingParticipantProvider} seam the intent's own creation
 *       gate uses ({@code PaymentsService.createIntent} requires CONFIRMED
 *       through it; this read requires COMPLETED through it). COMPLETED ⇒
 *       eligible; any other readable state ⇒
 *       {@code DOMAIN_END_NOT_REACHED} (the money is collected but the
 *       service has not completed — the operational worklist).</li>
 *   <li><b>ORDER</b> — "the order's FULFILLED terminal state": the
 *       measured absence this read refuses to paper over. No existing port
 *       carries the order's state into the payments module
 *       ({@code OrderSellerPort} answers only the seller), so the honest
 *       answer is {@code DOMAIN_END_UNKNOWN} — NOT eligible on this
 *       read's own authority; the operator verifies fulfillment on the
 *       order's own surface before paying out. Widening this needs a
 *       decision (a fulfillment read port), not an invention here.</li>
 *   <li><b>LOAN</b> — ADR-0013 D3's own words: "a loan fee settles on
 *       collection". The fee's settlement edge IS the collected intent
 *       ({@code LoanClosedEvent} exists but has no ledger consumer — the
 *       matrix measured it; the ADR did not make closure the loan fee's
 *       eligibility input). Collected and not refunded ⇒ eligible.</li>
 *   <li><b>AD</b> — "an ad's is its charged window": the billing run
 *       freezes the window's consumption AFTER the window ran
 *       ({@code AdBillingBatchExecutor} bills the open
 *       {@code [billedThrough, horizon)} window it just measured), so the
 *       AD-origin intent IS the charged window's bill. Collected and not
 *       refunded ⇒ eligible.</li>
 *   <li><b>REFUNDED / PARTIALLY_REFUNDED</b> — the refund facts the
 *       intent itself carries: a full refund's debit already mirrored the
 *       credit; a partial refund is ADR-0013 D3's deliberate ledger no-op,
 *       and the remainder's settlement treatment is the owner's decision —
 *       the row carries both amounts so the operator sees the remainder
 *       without this read inventing arithmetic for it.</li>
 * </ul>
 *
 * <p><b>The holds that are NOT here (measured absences, owner
 * decisions):</b> ADR-0013 D3's hold stage — open disputes and the refund
 * window — has no facts in the code: an open dispute does not appear on
 * the intent (only its REFUND_CONSUMER resolution does, as a refund), and
 * no refund-window column exists anywhere. This read therefore does not
 * block on them and does not invent them; surfacing them requires their
 * own facts first.
 *
 * <p><b>DELIVERY is structurally out</b> — the ledger's completion
 * listener skips that origin (the dedicated delivery journal is its own
 * settlement gate, the only one the matrix measured in the code), so no
 * DELIVERY credit exists for this read to derive over.
 *
 * <p><b>Shape:</b> the provider's credits arrive whole from
 * {@code LedgerReadPort} (one provider's own transaction history — the
 * {@code DeliveryReconciliationService} in-memory-accumulation precedent;
 * a platform-wide aggregation would need its own bounded window and is not
 * this v1), the intents resolve in ONE batched repository read (the V229
 * N+1 lesson), and the page is the deterministic
 * newest-first/id-DESC slice the credits already carry.
 */
@Service
@Transactional(readOnly = true)
public class SettlementEligibilityService {

    private final LedgerReadPort ledgerReadPort;
    private final PaymentIntentRepository paymentIntentRepository;
    private final BookingParticipantProvider bookingParticipantProvider;

    public SettlementEligibilityService(LedgerReadPort ledgerReadPort,
                                         PaymentIntentRepository paymentIntentRepository,
                                         BookingParticipantProvider bookingParticipantProvider) {
        this.ledgerReadPort = ledgerReadPort;
        this.paymentIntentRepository = paymentIntentRepository;
        this.bookingParticipantProvider = bookingParticipantProvider;
    }

    /**
     * One provider's settlement-eligibility report: the ledger balances,
     * the per-{@code (origin, currency)} totals over the provider's whole
     * credit set, and the paged per-intent breakdown with the honest
     * reason per row.
     *
     * @param providerId the provider whose credits the report derives over
     *        (required — eligibility is a per-provider payout-preparation
     *        question; a missing id answers the house 400 before any read)
     * @param origin optional origin filter over the derived rows — an
     *        unknown origin word honestly answers the empty report (the
     *        admin search's own rule: origins the running system does not
     *        know cannot have rows)
     * @param pageable the breakdown page request
     */
    @Observed(name = "payments.settlement.eligibility")
    public SettlementEligibilityReport eligibility(UUID providerId, String origin, Pageable pageable) {
        if (providerId == null) {
            throw new BadRequestException(
                    "The settlement-eligibility read requires a providerId — eligibility is a "
                            + "per-provider payout-preparation question (ADR-0013 D4)");
        }
        List<LedgerReadPort.SettlementCredit> credits = ledgerReadPort.findCreditsByProvider(providerId);
        // The V229 lesson (CodeRabbit round 1 on #630): the whole set's
        // intents resolve in ONE batched read by primary key.
        Map<UUID, PaymentIntent> intentsById = paymentIntentRepository
                .findAllById(credits.stream()
                        .map(LedgerReadPort.SettlementCredit::paymentIntentId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(PaymentIntent::getId, Function.identity()));

        List<SettlementEligibilityReport.SettlementEligibilityRow> rows = new ArrayList<>(credits.size());
        for (LedgerReadPort.SettlementCredit credit : credits) {
            PaymentIntent intent = intentsById.get(credit.paymentIntentId());
            // The origin word is payments-owned data (the W1 decision) —
            // the filter compares it as the plain word it is; a credit
            // whose intent row is gone filters out honestly (the
            // derivation's own defensive branch documents that shape).
            if (origin != null && (intent == null || !origin.equals(intent.getOrigin()))) {
                continue;
            }
            rows.add(derive(credit, intent));
        }

        Map<String, long[]> totals = new LinkedHashMap<>();
        for (SettlementEligibilityReport.SettlementEligibilityRow row : rows) {
            long[] bucket = totals.computeIfAbsent(row.origin() + ":" + row.currency(),
                    unused -> new long[]{0L, 0L, 0L});
            bucket[0]++;                              // creditCount
            bucket[1] += row.creditedCents();          // creditedCents
            if (row.eligible()) {
                bucket[2] += row.creditedCents();      // eligibleCents
            }
        }
        List<SettlementEligibilityReport.OriginCurrencyTotal> totalRows = totals.entrySet().stream()
                .map(entry -> {
                    String[] originAndCurrency = entry.getKey().split(":", 2);
                    long[] bucket = entry.getValue();
                    return new SettlementEligibilityReport.OriginCurrencyTotal(
                            originAndCurrency[0], originAndCurrency[1],
                            (int) bucket[0], bucket[1], bucket[2]);
                })
                .toList();

        // The credits arrive newest-first (the id-DESC tiebreak) and the
        // derivation preserves that order — the provider statement's own
        // deterministic paging law, so the slice is stable across ties.
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), rows.size());
        return new SettlementEligibilityReport(
                providerId,
                ledgerReadPort.findBalancesByProvider(providerId),
                totalRows,
                PagedResponse.of(new PageImpl<>(rows.subList(from, to), pageable, rows.size())));
    }

    /**
     * The law applied to one settled credit. The order of the questions
     * IS the law's precedence: the refund facts the intent itself carries
     * first (the credit's money may already be gone), then the origin's
     * own eligibility fact.
     */
    private SettlementEligibilityReport.SettlementEligibilityRow derive(
            LedgerReadPort.SettlementCredit credit, PaymentIntent intent) {
        if (intent == null) {
            // Payment intents have no delete path — the honest defensive
            // branch, never an expected row.
            return row(credit, "UNKNOWN", null, credit.amountCents(), 0L, false,
                    SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
        }
        switch (intent.getStatus()) {
            case REFUNDED -> {
                return row(credit, intent.getOrigin(), subjectOf(intent), intent.getAmountCents(),
                        intent.getRefundedAmountCents(), false,
                        SettlementEligibilityReport.REASON_REFUNDED);
            }
            case PARTIALLY_REFUNDED -> {
                return row(credit, intent.getOrigin(), subjectOf(intent), intent.getAmountCents(),
                        intent.getRefundedAmountCents(), false,
                        SettlementEligibilityReport.REASON_PARTIALLY_REFUNDED);
            }
            case SUCCEEDED -> {
                return deriveDomainEnd(credit, intent);
            }
            // CREATED/PROCESSING/FAILED/CANCELLED cannot own a settled
            // credit (credits land on COMPLETED only) — the honest
            // fail-closed branch, never an expected row.
            default -> {
                return row(credit, intent.getOrigin(), subjectOf(intent), intent.getAmountCents(),
                        intent.getRefundedAmountCents(), false,
                        SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
            }
        }
    }

    /**
     * The collected-and-not-refunded credit's domain end — the per-origin
     * branch, fail-closed like the ledger listener's own dispatch: an
     * origin this law does not know can never fall through to a booking
     * lookup that would read the WRONG fact.
     */
    private SettlementEligibilityReport.SettlementEligibilityRow deriveDomainEnd(
            LedgerReadPort.SettlementCredit credit, PaymentIntent intent) {
        // BOOKING: ADR-0013 D3 — "a booking's settlement eligibility is
        // its COMPLETED state", through the same seam the creation gate
        // uses.
        if (PaymentIntent.ORIGIN_BOOKING.equals(intent.getOrigin())) {
            try {
                BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.getBookingId());
                if ("COMPLETED".equals(bookingInfo.status())) {
                    return row(credit, intent.getOrigin(), intent.getBookingId(), intent.getAmountCents(),
                            0L, true, SettlementEligibilityReport.REASON_SETTLED_NOT_REFUNDED);
                }
                return row(credit, intent.getOrigin(), intent.getBookingId(), intent.getAmountCents(),
                        0L, false, SettlementEligibilityReport.REASON_DOMAIN_END_NOT_REACHED);
            } catch (ResourceNotFoundException unknown) {
                // Bookings have no delete path either — the honest
                // defensive branch.
                return row(credit, intent.getOrigin(), intent.getBookingId(), intent.getAmountCents(),
                        0L, false, SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
            }
        }
        // ORDER: ADR-0013 D3 names FULFILLED; the measured absence — no
        // port carries the order's state here. The honest reason, never
        // an invented fact.
        if (PaymentIntent.ORIGIN_ORDER.equals(intent.getOrigin())) {
            return row(credit, intent.getOrigin(), intent.getOrderId(), intent.getAmountCents(),
                    0L, false, SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
        }
        // LOAN: ADR-0013 D3 — "a loan fee settles on collection".
        if (PaymentIntent.ORIGIN_LOAN.equals(intent.getOrigin())) {
            return row(credit, intent.getOrigin(), intent.getLoanId(), intent.getAmountCents(),
                    0L, true, SettlementEligibilityReport.REASON_SETTLED_NOT_REFUNDED);
        }
        // AD: the charged window — the intent IS that window's bill (the
        // billing run freezes a window that already ran, then issues this
        // intent for it).
        if (PaymentIntent.ORIGIN_AD.equals(intent.getOrigin())) {
            return row(credit, intent.getOrigin(), intent.getAdCampaignId(), intent.getAmountCents(),
                    0L, true, SettlementEligibilityReport.REASON_SETTLED_NOT_REFUNDED);
        }
        // DELIVERY and anything else: no PAYMENT_CREDIT can exist for
        // DELIVERY (the listener skips it structurally), so this is the
        // fail-closed branch for the impossible — honest, never a silent
        // fallback.
        return row(credit, intent.getOrigin(), subjectOf(intent), intent.getAmountCents(),
                0L, false, SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
    }

    /** The origin-agnostic subject id — whichever column the origin carries. */
    private UUID subjectOf(PaymentIntent intent) {
        if (intent.getBookingId() != null) {
            return intent.getBookingId();
        }
        if (intent.getOrderId() != null) {
            return intent.getOrderId();
        }
        if (intent.getLoanId() != null) {
            return intent.getLoanId();
        }
        if (intent.getAdCampaignId() != null) {
            return intent.getAdCampaignId();
        }
        return intent.getDeliveryRequestId();
    }

    private SettlementEligibilityReport.SettlementEligibilityRow row(
            LedgerReadPort.SettlementCredit credit, String origin, UUID subjectId,
            long intentAmountCents, long refundedAmountCents, boolean eligible, String reason) {
        return new SettlementEligibilityReport.SettlementEligibilityRow(
                credit.paymentIntentId(),
                credit.providerId(),
                origin,
                subjectId,
                intentAmountCents,
                credit.amountCents(),
                refundedAmountCents,
                credit.currency(),
                eligible,
                reason);
    }
}
