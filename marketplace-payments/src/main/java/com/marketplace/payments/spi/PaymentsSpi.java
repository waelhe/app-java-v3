package com.marketplace.payments.spi;

import com.marketplace.shared.api.PaymentSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * SPI for cross-module access to payment operations.
 */
public interface PaymentsSpi {

    Page<PaymentSummary> listIntentsSummaries(Pageable pageable);

    PaymentSummary getIntentSummary(UUID id);
}
