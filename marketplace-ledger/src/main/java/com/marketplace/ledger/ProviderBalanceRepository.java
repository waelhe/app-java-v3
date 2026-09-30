package com.marketplace.ledger;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProviderBalanceRepository
        extends JpaRepository<ProviderBalance, ProviderBalance.ProviderBalanceId>,
                RevisionRepository<ProviderBalance, ProviderBalance.ProviderBalanceId, Integer> {

    /**
     * R9 (comprehensive-review-ar-fix plan §4/R9): the provider's balances,
     * one row per currency he holds — the multi-currency read surface
     * (ordered by currency for a deterministic response shape).
     */
    List<ProviderBalance> findByProviderIdOrderByCurrencyAsc(UUID providerId);
}
