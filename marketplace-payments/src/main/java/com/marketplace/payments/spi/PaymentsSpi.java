package com.marketplace.payments.spi;

import com.marketplace.shared.api.PaymentSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;

import java.util.UUID;

/**
 * SPI for cross-module access to payment operations.
 */
@NamedInterface("payments-spi")
public interface PaymentsSpi {

    Page<PaymentSummary> listIntentsSummaries(Pageable pageable);

    PaymentSummary getIntentSummary(UUID id);
}
