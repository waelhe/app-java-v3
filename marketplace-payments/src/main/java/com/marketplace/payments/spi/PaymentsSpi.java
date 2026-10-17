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
}
