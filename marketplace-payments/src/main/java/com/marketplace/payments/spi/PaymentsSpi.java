package com.marketplace.payments.spi;

import com.marketplace.shared.api.PaymentSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * SPI for cross-module access to payment operations.
 *
 * <p>Widened by the payments plan's Phase-1 unified finance-operator read
 * model: the operator's single-search surface rides the same seam the
 * plain list already rides (the admin composition layer stays a consumer
 * of this SPI — it never queries the payments module's storage itself).
 *
 * <p>Widened again by W11 (the origin-state matrix's G5 closure —
 * ADR-0013 D3): the settlement-eligibility operational read rides the
 * same seam — the eligibility law (which settled credit's owning domain
 * reached its proper end) is payments-owned, so the admin surface consumes
 * the derived report rather than composing the facts itself.
 */
@NamedInterface("payments-spi")
public interface PaymentsSpi {

    Page<PaymentSummary> listIntentsSummaries(Pageable pageable);

    /**
     * The operator's search: the AND of every present field over the
     * intent root (origin, status, the origin-agnostic subject id, the
     * payer, the exclusive-end creation window). A null/neutral search
     * answers the plain list — the semantic the list method already owns.
     */
    Page<PaymentSummary> searchIntentsSummaries(PaymentIntentSearch search, Pageable pageable);

    PaymentSummary getIntentSummary(UUID id);

    /**
     * W11 (the matrix's G5): one provider's settlement-eligibility report
     * — the read-only derivation above the ledger's settled credits and
     * the intent facts. READ-ONLY by contract: no state machine, no money
     * movement (ADR-0013 D4 keeps payout a separate operational stage).
     *
     * @param providerId the provider whose credits the report derives
     *        over (required — the house 400 when absent)
     * @param origin optional origin filter over the derived rows
     * @param pageable the per-intent breakdown page
     */
    SettlementEligibilityReport settlementEligibility(UUID providerId, String origin, Pageable pageable);
}
