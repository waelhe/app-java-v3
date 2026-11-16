package com.marketplace.ledger.spi;

import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.shared.api.CurrencyAmount;
import com.marketplace.shared.api.LedgerStatsPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): the ledger module's implementation of
 * the {@link LedgerStatsPort} cross-module contract (the
 * {@code ReviewStatsAdapter} house pattern). The net computation lives here
 * because the ledger owns the entry types and their signs — the provider
 * module never learns how a credit, a commission debit and a refund debit
 * combine.
 *
 * <p><b>R9 (comprehensive-review-ar-fix plan §4/R9):</b> the port answers
 * PER CURRENCY — the repository groups by the entry's ISO 4217 code, so the
 * provider stats surface carries one number per currency instead of the
 * pre-fix single sum that mixed them.</p>
 */
@Component
@Transactional(readOnly = true)
public class LedgerStatsAdapter implements LedgerStatsPort {

    private final LedgerEntryRepository entryRepository;

    public LedgerStatsAdapter(LedgerEntryRepository entryRepository) {
        this.entryRepository = entryRepository;
    }

    @Override
    public List<CurrencyAmount> findNetByCurrencyForProviderBetween(UUID providerId, Instant from, Instant to) {
        return entryRepository.sumNetCentsByCurrencyForProviderBetween(providerId, from, to).stream()
                .map(row -> new CurrencyAmount((String) row[0], ((Number) row[1]).longValue()))
                .toList();
    }
}
