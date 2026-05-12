package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentLookupPort {
    Optional<PaymentIntentDetails> findById(UUID paymentIntentId);
}
