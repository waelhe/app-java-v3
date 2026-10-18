package com.marketplace.shared.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentLookupPort {

    Optional<PaymentIntentDetails> findById(UUID paymentIntentId);

    /**
     * The V229 reconciliation's batch read (CodeRabbit round 1 on #630,
     * verified real — the per-leg findById N+1 over a historical window):
     * resolves the whole window's quote-borne intents in ONE repository
     * round trip. Absent ids are simply missing from the result — the
     * caller's own absent-intent semantics apply per leg.
     */
    List<PaymentIntentDetails> findAllById(Collection<UUID> paymentIntentIds);
}
